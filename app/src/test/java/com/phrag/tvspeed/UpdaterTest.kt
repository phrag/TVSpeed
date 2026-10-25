package com.phrag.tvspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {

    private val sha = "a".repeat(40)
    private val json = """{"sha":"$sha","time":1792950000,"apk":"app.apk","sha256":"${"b".repeat(64)}","size":834640}"""

    @Test fun parsesVersionJson() {
        val r = Updater.parse(json)
        assertEquals(sha, r.sha)
        assertEquals(1792950000L, r.time)
        assertEquals("app.apk", r.apk)
        assertEquals(834640L, r.size)
    }

    @Test fun newerOnlyForADifferentLaterCommit() {
        val r = Updater.parse(json)
        assertTrue(r.isNewerThan("c".repeat(40), 1792940000))
        assertFalse("same build", r.isNewerThan(sha, 1792940000))
        assertFalse("local build is newer", r.isNewerThan("c".repeat(40), 1792960000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPathInApkName() {
        Updater.parse(json.replace("app.apk", "../evil.apk"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBadHash() {
        Updater.parse(json.replace("b".repeat(64), "nothex"))
    }
}

class SignerTest {
    @Test fun sameSignerAccepted() = org.junit.Assert.assertTrue(Updater.signerAccepted(setOf("a"), setOf("a"), setOf("a")))
    @Test fun otherSignerRefused() = org.junit.Assert.assertFalse(Updater.signerAccepted(setOf("a"), setOf("b"), setOf("b")))
    @Test fun rotatedSignerAccepted() = org.junit.Assert.assertTrue(Updater.signerAccepted(setOf("a"), setOf("b"), setOf("a", "b")))
    @Test fun unreadableInstalledRefused() = org.junit.Assert.assertFalse(Updater.signerAccepted(emptySet(), emptySet(), emptySet()))
}
