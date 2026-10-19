package com.voxturr.mirage.host.privileged;

/**
 * The optional Shizuku route to "Send my screen": a Shizuku UserService (PrivilegedService)
 * running as the shell user, hosting the same HostSession that ServerMain runs when the Mac
 * starts it over adb. The app only drives it and polls its status.
 *
 * Explicit transaction codes because Shizuku calls 16777114 itself to tear the service down.
 */
interface IPrivilegedService {
    /** Shizuku calls this when the app unbinds with remove = true. Stops the host, then exits. */
    void destroy() = 16777114;

    /**
     * Starts (or restarts) the host with ServerMain's arguments
     * (`--host <ip> --port <n> [--code c] …`, see ServerArgs). Returns the first status line,
     * or `ERR <reason>` if the arguments are invalid.
     */
    String startHost(in String[] args) = 1;

    /** Bye to the Mac, display released, settings restored. */
    void stopHost() = 2;

    /** Latest status line: IDLE, CONNECTING …, CONNECTED, OK …, ERR …, BYE. */
    String status() = 3;

    /** Latest `STATS …` line, or empty while not streaming. */
    String stats() = 4;

    /** Starts "pkg" (its launcher activity) or "pkg/cls" as a window on the display. */
    boolean launchApp(String packageOrComponent, int displayId) = 5;
}
