package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** The security hardening: approvals by label and by app, notification rules, Linux tools beside background mode. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class HardeningTest {
    private final Context c = RuntimeEnvironment.getApplication();

    // ------------------------------------------------------------------ risky button labels

    @Test
    public void riskyWordsAnywhereInAShortLabel() {
        for (String s : new String[]{"Place your order", "Yes, delete", "Approve", "Accept", "Complete purchase",
                "Agree and pay", "Pay £49.99", "Send", "Proceed to checkout", "Authorise", "Order history"}) {
            assertNotNull("should ask: " + s, Guard.riskyText(s));
        }
    }

    @Test
    public void plainButtonsAndLookalikeWordsGoThrough() {
        for (String s : new String[]{"Next", "Done", "Open chat", "Search", "Recent calls", "Settings", "Back",
                "Sendai trip", "Payload", "Callum", "Ordering tips and more", "Silver", "Postcode", "Calligraphy"}) {
            assertNull("should not ask: " + s, Guard.riskyText(s));
        }
    }

    // ------------------------------------------------------------------ locked-down apps

    @Test
    public void builtInSensitiveAppsAreLockedDown() {
        for (String p : new String[]{"com.whatsapp", "com.google.android.gm", "com.android.settings",
                "com.android.vending", "uk.co.santander.bank", "com.example.wallet", "org.fdroid.fdroid"}) {
            assertTrue("locked down: " + p, Guard.lockedDown(c, p));
        }
        assertTrue("an unknown package is locked down", Guard.lockedDown(c, null));
    }

    @Test
    public void shoppingAppsAndBrowsersAreLockedDown() {
        for (String p : new String[]{"com.amazon.mShop.android.shopping", "com.ebay.mobile", "com.einnovation.temu",
                "com.zzkko", "com.tesco.grocery.view", "uk.co.deliveroo.orderapp", "fr.vinted"}) {
            assertTrue("locked down: " + p, Guard.lockedDown(c, p));
        }
        String browser = "org.example.browser";
        android.content.Intent view = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://example.com/")).addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                .setPackage(browser);
        android.content.pm.ResolveInfo ri = new android.content.pm.ResolveInfo();
        ri.activityInfo = new android.content.pm.ActivityInfo();
        ri.activityInfo.packageName = browser;
        ri.activityInfo.name = browser + ".Main";
        org.robolectric.Shadows.shadowOf(c.getPackageManager()).addResolveInfoForIntent(view, ri);
        assertTrue("a browser", Guard.lockedDown(c, browser));
    }

    @Test
    public void ordinaryAppsAndTrustedAppsAreNot() {
        assertFalse(Guard.lockedDown(c, "com.example.notes"));
        Prefs.setAppSafety(c, "com.whatsapp", "trust");
        assertFalse("the user's Trust wins", Guard.lockedDown(c, "com.whatsapp"));
    }

    @Test
    public void shopsAndBrowsersAreNeverSuggestedTrust() {
        Prefs.setAutonomousDefault(c, true); // "work on its own" at setup
        assertEquals("trust", Guard.mode(c, "com.example.notes", null));
        assertEquals("hard", Guard.mode(c, "com.amazon.mShop.android.shopping", null));
        assertEquals("hard", Guard.mode(c, "com.einnovation.temu", null));
        Prefs.setAppSafety(c, "com.ebay.mobile", "trust");
        assertEquals("the user's own Trust", "trust", Guard.mode(c, "com.ebay.mobile", null));
        assertFalse(Guard.lockedDown(c, "com.ebay.mobile"));
    }

    @Test
    public void inTheBackgroundBrowserOnlyPlainLinksGoWithoutAsking() throws Exception {
        assertTrue(BrowserTools.plainLink(new JSONObject().put("role", "link").put("href", "/orders")));
        assertTrue(BrowserTools.plainLink(new JSONObject().put("role", "link").put("href", "https://example.com/a")));
        assertFalse(BrowserTools.plainLink(new JSONObject().put("role", "link").put("href", "#")));
        assertFalse(BrowserTools.plainLink(new JSONObject().put("role", "link").put("href", " JavaScript:pay()")));
        assertFalse(BrowserTools.plainLink(new JSONObject().put("role", "link").put("href", "")));
        assertFalse(BrowserTools.plainLink(new JSONObject().put("role", "button").put("href", "/orders")));
        assertFalse(BrowserTools.plainLink(new JSONObject().put("role", "input")));
    }

    // ------------------------------------------------------------------ shells and background mode

    private static void stepTo(String name) throws Exception {
        Field f = Background.class.getDeclaredField("step");
        f.setAccessible(true);
        for (Object o : Class.forName("app.buddy.assistant.Background$Step").getEnumConstants()) {
            if (((Enum<?>) o).name().equals(name)) f.set(null, o);
        }
    }

    @Test
    public void everyShellRouteIsRefusedBesideBackgroundMode() throws Exception {
        assertTrue(Background.shellsAllowed(c));
        assertTrue("a shell marks Buddy's Linux", Prefs.devShellUsed(c));
        Prefs.setBackgroundPaired(c, true);
        assertFalse(Background.shellsAllowed(c));
        try {
            Background.requireShells(c); // the terminal, Remote Control, Codex's remote, the SSH server
            throw new AssertionError("a shell started beside background mode");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("background mode"));
        }
        Prefs.setBackgroundPaired(c, false);
        stepTo("PAIRING");
        try {
            assertFalse("while setting up", Background.shellsAllowed(c));
        } finally {
            stepTo("IDLE");
        }
    }

    @Test
    public void restoringABackupKeepsTheSafetySettings() throws Exception {
        Method stays = Backup.class.getDeclaredMethod("stays", String.class);
        stays.setAccessible(true);
        assertTrue((Boolean) stays.invoke(null, "dev_shell_used"));
        assertTrue((Boolean) stays.invoke(null, "app_private_uk.co.santander.bank"));
        assertFalse((Boolean) stays.invoke(null, "app_safety_com.example.notes"));
    }

    // ------------------------------------------------------------------ private apps

    @Test
    public void moneyAppsStartPrivateAndTheUserDecides() {
        assertTrue(Guard.privateApp(c, "uk.co.santander.bank"));
        assertTrue(Guard.privateApp(c, "com.paypal.android.p2pmobile"));
        assertFalse(Guard.privateApp(c, "com.example.notes"));
        assertFalse("Buddy itself", Guard.privateApp(c, "app.buddy.assistant"));
        assertFalse("no app", Guard.privateApp(c, null));
        Prefs.setAppPrivate(c, "com.example.notes", true);
        assertTrue(Guard.privateApp(c, "com.example.notes"));
        Prefs.setAppPrivate(c, "uk.co.santander.bank", false);
        assertFalse(Guard.privateApp(c, "uk.co.santander.bank"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void aViewApprovalIsPerChatAndAppAndEnds() throws Exception {
        String bank = "uk.co.santander.bank";
        assertFalse(Guard.mayView("chat1", bank));
        Guard.allowView("chat1", bank);
        assertTrue(Guard.mayView("chat1", bank));
        assertFalse("another chat", Guard.mayView("chat2", bank));
        assertFalse("another app", Guard.mayView("chat1", "com.whatsapp"));
        Guard.forgetViews("chat1");
        assertFalse("the run ended", Guard.mayView("chat1", bank));

        Guard.allowView("outside", bank);
        Field f = Guard.class.getDeclaredField("SEEN");
        f.setAccessible(true);
        ((java.util.Map<String, Long>) f.get(null)).put("outside|" + bank, System.currentTimeMillis() - 31 * 60_000L);
        assertFalse("30 minutes at most", Guard.mayView("outside", bank));
    }

    // ------------------------------------------------------------------ notification rules

    @Test
    public void savedRulesWithCountdownOrDontAskReadAsAsk() throws Exception {
        for (String a : new String[]{"countdown", "now", "ask", ""}) {
            Rules.Rule r = Rules.Rule.of(new JSONObject().put("approval", a).put("seconds", 10));
            assertEquals("ask", r.approval);
            assertFalse(r.countdown());
            assertFalse(r.now());
        }
    }

    // ------------------------------------------------------------------ Linux tools beside background mode

    private static String devArg(Sessions.Session s) throws Exception {
        Method m = ChatHub.class.getDeclaredMethod("devArg", Sessions.Session.class);
        m.setAccessible(true);
        return (String) m.invoke(null, s);
    }

    private static void step(String name) throws Exception {
        Field f = Background.class.getDeclaredField("step");
        f.setAccessible(true);
        Class<?> e = Class.forName("app.buddy.assistant.Background$Step");
        for (Object o : e.getEnumConstants()) if (((Enum<?>) o).name().equals(name)) f.set(null, o);
    }

    @Test
    public void linuxToolsOnlyWithoutBackgroundMode() throws Exception {
        Sessions.Session s = new Sessions.Session("t1", "claude");
        s.dev = false;
        assertEquals("0", devArg(s));
        assertFalse("no shell, nothing marked", Prefs.devShellUsed(c));

        s.dev = true;
        assertEquals("1", devArg(s));
        assertTrue("a shell marks Buddy's Linux", Prefs.devShellUsed(c));

        Prefs.setBackgroundPaired(c, true);
        assertEquals("paired: no shell", "0", devArg(s));
        Prefs.setBackgroundPaired(c, false);

        step("WAITING");
        try {
            assertEquals("setting up: no shell", "0", devArg(s));
        } finally {
            step("IDLE");
        }
        assertEquals("1", devArg(s));
    }
}
