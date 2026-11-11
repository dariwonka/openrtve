package es.openrtve.data

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CatalogBackupTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun backup(dir: File) = CatalogBackup(File(dir, "catalog.json"), File(dir, "history.json"))

    @Test
    fun `a backup restores catalog and history into another install`() = runBlocking {
        val from = folder.newFolder("from")
        File(from, "catalog.json").writeText("""{"series":[],"pinned":["1"]}""")
        File(from, "history.json").writeText("""[{"id":"7"}]""")
        val raw = backup(from).export()

        val to = folder.newFolder("to")
        assertTrue(backup(to).import(raw))
        assertEquals("""{"series":[],"pinned":["1"]}""", File(to, "catalog.json").readText())
        assertEquals("""[{"id":"7"}]""", File(to, "history.json").readText())
    }

    @Test
    fun `a file that is not a backup changes nothing`() = runBlocking {
        val dir = folder.newFolder("dir")
        File(dir, "catalog.json").writeText("""{"series":[]}""")
        val target = backup(dir)
        assertFalse(target.import("not json"))
        assertFalse(target.import("""{"version":9,"catalog":"{}"}"""))
        assertFalse(target.import("""{"version":1,"catalog":"{roto","history":""}"""))
        assertEquals("""{"series":[]}""", File(dir, "catalog.json").readText())
    }
}
