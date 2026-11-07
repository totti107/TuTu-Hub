package com.arena.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterTest {
    @Test fun readsTheLatestRelease() {
        val r = Updater.parse("""{"tag_name":"v3.6.1-1007","name":"TuTu 3.6.1","body":"Faster guide\n","assets":[
            {"name":"notes.txt","browser_download_url":"https://x/notes.txt"},
            {"name":"TuTuTV-v3.6.1.apk","browser_download_url":"https://github.com/totti107/TuTu-hub/releases/download/v3.6.1-1007/TuTuTV-v3.6.1.apk"}]}""")!!
        assertEquals(1007, r.build)
        assertEquals("3.6.1", r.version)
        assertEquals("Faster guide", r.notes)
        assertEquals("https://github.com/totti107/TuTu-hub/releases/download/v3.6.1-1007/TuTuTV-v3.6.1.apk", r.apkUrl)
    }

    @Test fun ignoresReleasesWithoutAnApk() {
        assertNull(Updater.parse("""{"tag_name":"v3.6.1-1007","assets":[]}"""))
        assertNull(Updater.parse("""{"tag_name":"something-else","assets":[{"name":"a.apk","browser_download_url":"u"}]}"""))
    }
}
