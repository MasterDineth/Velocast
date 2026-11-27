package com.mastersoft.velocast.server

import com.mastersoft.velocast.server.platform.ShellIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeContextIdentityTest {

    @Test
    fun defaultsToComAndroidShellAndDerivesUidAwareness() {
        val shell = ShellIdentity(uid = ShellIdentity.SHELL_UID)
        assertEquals("com.android.shell", shell.packageName)
        assertEquals("com.android.shell", shell.opPackageName)
        assertNull(shell.attributionTag)
        assertEquals(0, shell.userId)
        assertTrue(shell.isShellUid)
        assertFalse(shell.isRootUid)

        val root = ShellIdentity(uid = ShellIdentity.ROOT_UID)
        assertEquals("com.android.shell", root.packageName)
        assertEquals(0, root.userId)
        assertFalse(root.isShellUid)
        assertTrue(root.isRootUid)

        val secondaryUserShell = ShellIdentity(
            uid = 10 * ShellIdentity.PER_USER_RANGE + ShellIdentity.SHELL_UID,
            attributionTag = "clipboard"
        )
        assertEquals(10, secondaryUserShell.userId)
        assertTrue(secondaryUserShell.isShellUid)
        assertFalse(secondaryUserShell.isRootUid)
        assertEquals("clipboard", secondaryUserShell.attributionTag)

        val regularApp = ShellIdentity(uid = 10_234)
        assertEquals(0, regularApp.userId)
        assertFalse(regularApp.isShellUid)
        assertFalse(regularApp.isRootUid)
    }
}
