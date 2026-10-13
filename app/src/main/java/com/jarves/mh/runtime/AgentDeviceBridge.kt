package com.jarves.mh.runtime

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import java.io.File
import java.security.SecureRandom
import org.json.JSONObject

/**
 * Turns the read-only query server into something a command-line agent can actually use.
 *
 * The agents run inside this app's own runtime and cannot ask Android anything. This bridge starts the
 * loopback server, writes a per-run token and a small Python wrapper into the runtime's `usr/bin` (so it
 * is on `PATH` inside the rootfs), and removes both again when the bridge stops. Everything it exposes
 * is read-only: device facts and the list of launcher apps. Installs, launches and file access are not
 * part of it — those need a visible confirmation the agent cannot give.
 */
class AgentDeviceBridge(private val context: Context) {

    companion object {
        const val COMMAND_NAME = "mh-device"

        /** Inside the rootfs, so `PATH` already contains it. */
        private const val COMMAND_PATH = "usr/bin/$COMMAND_NAME"
        private const val TOKEN_PATH = "usr/local/share/mh-device/token"
        private const val ROOTFS_DIR = "runtime/ubuntu"
        private const val MAX_APPS = DeviceQueryServer.MAX_APPS_LIMIT

        private val TOKEN_ALPHABET = "0123456789abcdef"

        fun newToken(random: SecureRandom = SecureRandom()): String {
            val builder = StringBuilder(32)
            repeat(32) { builder.append(TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)]) }
            return builder.toString()
        }

        /** True when the rootfs exists, i.e. the runtime has been installed at least once. */
        fun hasRuntime(context: Context): Boolean = File(context.filesDir, ROOTFS_DIR).isDirectory

        /**
         * The wrapper the agent calls. It reads the token from the file beside it, talks only to the
         * loopback address and prints the JSON it gets; a refusal is printed as-is instead of being
         * turned into an empty success.
         */
        fun wrapperScript(port: Int): String = """
            #!/usr/bin/env python3
            '''Read-only device facts for coding agents (served by Mobile Harness on this device).'''
            import json, sys, urllib.request, urllib.error, os

            PORT = $port
            BASE = "http://127.0.0.1:%d" % PORT
            TOKEN_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "local", "share", "mh-device", "token")

            USAGE = '''mh-device - read-only device facts for coding agents

            Usage:
              mh-device info             Android version, API level, ABIs, page size, free storage, RAM
              mh-device apps [--offset N] [--limit N]
                                         installed launcher apps, paged (max $MAX_APPS per page)

            Read-only by design: nothing here installs, launches or reads your files.'''

            def token():
                try:
                    with open(TOKEN_FILE, "r", encoding="utf-8") as handle:
                        return handle.read().strip()
                except OSError:
                    return ""

            def call(path):
                request = urllib.request.Request(BASE + path, headers={"X-MH-Token": token()})
                try:
                    with urllib.request.urlopen(request, timeout=5) as response:
                        return 0, response.read().decode("utf-8")
                except urllib.error.HTTPError as error:
                    return error.code, error.read().decode("utf-8", "replace")
                except Exception as failure:
                    return 1, json.dumps({"error": "bridge_unreachable", "detail": str(failure)})

            def main(argv):
                if not argv or argv[0] in ("-h", "--help", "help"):
                    print(USAGE)
                    return 0
                command = argv[0]
                if command == "info":
                    code, body = call("/device/info")
                elif command == "apps":
                    offset, limit = 0, 100
                    rest = argv[1:]
                    while rest:
                        flag = rest.pop(0)
                        if flag == "--offset" and rest:
                            offset = int(rest.pop(0))
                        elif flag == "--limit" and rest:
                            limit = int(rest.pop(0))
                        else:
                            print("error: unknown argument %s" % flag, file=sys.stderr)
                            return 2
                    code, body = call("/apps?offset=%d&limit=%d" % (offset, limit))
                else:
                    print("error: unknown command %s" % command, file=sys.stderr)
                    print(USAGE)
                    return 2
                try:
                    print(json.dumps(json.loads(body), indent=2, ensure_ascii=False))
                except ValueError:
                    print(body)
                return 0 if code == 200 else 3

            if __name__ == "__main__":
                sys.exit(main(sys.argv[1:]))
        """.trimIndent()

        /** A short, honest note the prompt can carry while the bridge is active. */
        fun promptNote(): String =
            "Read-only device facts are available in the shell: run `$COMMAND_NAME info` for the Android " +
                "version, page size, storage and RAM, or `$COMMAND_NAME apps` for the installed launcher " +
                "apps. The command only reads; it cannot install or start anything."
    }

    private var server: DeviceQueryServer? = null
    private var rootfs: File? = null

    val isRunning: Boolean get() = server?.isRunning == true
    val port: Int get() = server?.boundPort ?: -1

    /** Starts the bridge for this app run; a second call is a no-op while one is running. */
    fun start(): Boolean {
        if (isRunning) return true
        val runtimeRoot = File(context.filesDir, ROOTFS_DIR)
        if (!runtimeRoot.isDirectory) return false
        rootfs = runtimeRoot
        val token = newToken()
        val query = DeviceQueryServer(
            port = 0,
            token = token,
            facts = ::deviceFacts,
            apps = ::launcherApps,
        )
        return runCatching {
            query.start()
            writeBridgeFiles(runtimeRoot, token, query.boundPort)
            server = query
            true
        }.getOrElse {
            query.stop()
            removeBridgeFiles(runtimeRoot)
            false
        }
    }

    fun stop() {
        server?.stop()
        server = null
        rootfs?.let { removeBridgeFiles(it) }
        rootfs = null
    }

    private fun deviceFacts(): JSONObject {
        val memory = ActivityManager.MemoryInfo().also {
            context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it)
        }
        val info = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val versionCode = info?.longVersionCode ?: 0L
        return DeviceQueryServer.Payload.deviceInfo(
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            apiLevel = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
            pageSizeBytes = NativeReadiness.pageSizeBytes(),
            freeBytes = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0L),
            totalRamBytes = memory.totalMem,
            appVersionName = info?.versionName.orEmpty(),
            appVersionCode = versionCode,
        )
    }

    /**
     * Launcher apps only: the manifest declares a `<queries>` entry for MAIN/LAUNCHER, so this needs no
     * QUERY_ALL_PACKAGES permission. The label is what a person recognises, the package is what a command
     * needs.
     */
    private fun launcherApps(offset: Int, limit: Int): JSONObject {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = context.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        val entries = activities
            .mapNotNull { resolve ->
                val packageName = resolve.activityInfo?.packageName ?: return@mapNotNull null
                val label = runCatching { resolve.loadLabel(context.packageManager).toString() }.getOrDefault(packageName)
                packageName to label
            }
            .distinctBy { it.first }
            .sortedWith(compareBy({ it.second.lowercase() }, { it.first }))
        return DeviceQueryServer.Payload.launcherApps(
            packages = entries.take(MAX_APPS * 4),
            offset = offset,
            limit = limit,
            total = entries.size,
        )
    }

    private fun writeBridgeFiles(runtimeRoot: File, token: String, port: Int) {
        val command = File(runtimeRoot, COMMAND_PATH)
        command.parentFile?.mkdirs()
        command.writeText(wrapperScript(port))
        command.setExecutable(true, false)
        command.setReadable(true, false)
        val tokenFile = File(runtimeRoot, TOKEN_PATH)
        tokenFile.parentFile?.mkdirs()
        tokenFile.writeText(token)
        // Owner-only: the file exists for this app's own runtime, not for anything else on the device.
        tokenFile.setReadable(false, false)
        tokenFile.setReadable(true, true)
        tokenFile.setWritable(false, false)
        tokenFile.setWritable(true, true)
    }

    private fun removeBridgeFiles(runtimeRoot: File) {
        runCatching { File(runtimeRoot, COMMAND_PATH).delete() }
        runCatching { File(runtimeRoot, TOKEN_PATH).delete() }
    }
}
