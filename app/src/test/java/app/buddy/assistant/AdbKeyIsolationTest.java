package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Buddy's real Linux.exec and Linux.execAdb, run with a real proot over a real Alpine root filesystem: an agent's shell
 * (exec) never sees background mode's adb key; Buddy's own adb commands (execAdb) do. Needs proot on the PATH and an
 * Alpine minirootfs given as -Pbuddy.alpine=<tar.gz> (and -Pandroid.useAndroidX=true, for Robolectric); skipped without them.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class AdbKeyIsolationTest {
    private static final String KEY = "TEST-ADB-PRIVATE-KEY";
    private Context c;

    @Before
    public void alpine() throws Exception {
        String tgz = System.getProperty("buddy.alpine", "");
        assumeTrue("no -Dbuddy.alpine", !tgz.isEmpty() && new File(tgz).isFile());
        assumeTrue("no proot", new File("/usr/bin/proot").canExecute());
        c = RuntimeEnvironment.getApplication();
        // Buddy runs proot from its native libraries: here, the system's proot (its loader is built in)
        File lib = new File(c.getCacheDir(), "lib");
        lib.mkdirs();
        File proot = new File(lib, "libproot.so");
        Files.write(proot.toPath(), ("#!/bin/sh\nunset PROOT_LOADER\n"
                // a distribution's proot may lack Termux's --kill-on-exit (reaps leftover processes) and --link2symlink (hard links
                // as symlinks): neither changes what a shell can see
                + "for a; do shift; case $a in --kill-on-exit|--link2symlink) ;; *) set -- \"$@\" \"$a\";; esac; done\n"
                + "exec /usr/bin/proot \"$@\"\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(proot.setExecutable(true));
        c.getApplicationInfo().nativeLibraryDir = lib.getAbsolutePath();
        File root = Linux.root(c);
        root.mkdirs();
        Process p = new ProcessBuilder("tar", "-xzf", tgz, "-C", root.getAbsolutePath()).inheritIO().start();
        assertEquals(0, p.waitFor());
        // a key where Buddy kept it before this change: in Alpine, at /root/.android
        File old = new File(root, "root/.android");
        old.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(old, "adbkey"))) {
            o.write(KEY.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final String READ = "cat /root/.android/adbkey 2>/dev/null || echo NO_KEY";

    @Test
    public void agentShellNoLongerSeesTheKey() throws Exception {
        // before Buddy's adb ran again: the old place still has it (the issue this fixes)
        String before = Linux.exec(c, READ, 60);
        assertTrue("reproduces the old exposure: " + before, before.contains(KEY));

        // Buddy's own adb command: the key is there for adb, and it moved out of Alpine
        String adb = Linux.execAdb(c, READ, 60);
        assertTrue("adb still has its key: " + adb, adb.contains(KEY));
        assertFalse("not in Alpine any more", new File(Linux.root(c), "root/.android/adbkey").exists());
        assertTrue("in Buddy's storage", new File(Linux.adbHome(c), "adbkey").exists());

        // an agent's shell: not at /root/.android, nowhere in Alpine, not by the key's real path
        String agent = Linux.exec(c, READ + "; grep -rl " + KEY + " /root /home /tmp /etc /usr /var /opt /srv /mnt 2>/dev/null | head -3; echo SCAN_DONE; "
                + "cat " + Linux.adbHome(c).getAbsolutePath() + "/adbkey 2>/dev/null || echo NO_HOST_PATH", 120);
        assertTrue(agent, agent.contains("NO_KEY"));
        assertTrue(agent, agent.contains("NO_HOST_PATH"));
        assertFalse("found by a scan: " + agent, agent.replace(READ, "").contains(KEY));

        // and Buddy's adb sees the same key on later runs
        assertTrue(Linux.execAdb(c, READ, 60).contains(KEY));
    }

    @Test
    public void agentCantReachTheKeyThroughProcWhileAdbRuns() throws Exception {
        // proot shares /proc: while Buddy's adb runs, a shell tries the key through every process's root there, by its
        // place in Alpine and by its real path. (Linux tools are off with background mode anyway, see ChatHub.devArg.)
        Linux.execAdb(c, "true", 60);
        Thread adb = new Thread(() -> {
            try {
                Linux.execAdb(c, "sleep 8", 30);
            } catch (Exception ignored) {
            }
        });
        adb.start();
        Thread.sleep(2500);
        String host = Linux.adbHome(c).getAbsolutePath() + "/adbkey";
        String agent = Linux.exec(c, "n=0; for d in /proc/[0-9]*; do n=$((n+1)); cat $d/root/root/.android/adbkey 2>/dev/null; "
                + "cat $d/root" + host + " 2>/dev/null; done; echo PROCS=$n; echo PROC_DONE", 60);
        adb.join();
        System.out.println("PROC_PROBE " + agent.replace('\n', ' '));
        assertTrue(agent, agent.contains("PROC_DONE"));
        assertFalse("reached through /proc: " + agent, agent.contains(KEY));
    }
}
