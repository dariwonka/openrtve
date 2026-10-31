package es.openrtve.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Genera por separado el arranque (layout del DEX) y los recorridos que ART precompila. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(
        packageName = PACKAGE,
        includeInStartupProfile = true,
        maxIterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.textContains("Explorar")), 15_000)
        device.waitForIdle()
    }

    /** Portada, scroll, Explorar, una categoría, búsqueda, una ficha y el reproductor. */
    @Test
    fun criticalUserJourneys() = rule.collect(
        packageName = PACKAGE,
        includeInStartupProfile = false,
        maxIterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.textContains("Explorar")), 15_000)
        device.waitForIdle()

        // Portada: recorrer filas.
        repeat(3) {
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 20)
            device.waitForIdle()
        }
        // Explorar y una categoría.
        device.findObject(By.text("Explorar"))?.click()
        device.wait(Until.hasObject(By.text("CINE")), 10_000)
        device.findObject(By.text("CINE"))?.click()
        device.wait(Until.hasObject(By.textContains("Directos")), 15_000)
        device.waitForIdle()
        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 20)
        device.waitForIdle()
        // Buscar y filtros rápidos.
        device.findObject(By.text("Buscar"))?.click()
        device.wait(Until.hasObject(By.textContains("SERIES")), 10_000)
        device.findObject(By.textContains("SERIES"))?.click()
        device.waitForIdle()
        // Ficha y reproductor mediante un programa estable: no dependemos del orden
        // cambiante de las tarjetas editoriales ni de los resultados del filtro.
        device.executeShellCommand(
            "am start -W -a android.intent.action.VIEW -d $PROGRAM_DEEP_LINK $PACKAGE/.ui.mobile.MainActivity",
        )
        device.wait(Until.hasObject(By.textContains("Reproducir")), 15_000)
        // La ficha carga capítulos, fragmentos/tráilers y detalles por separado.
        // Recorremos las pestañas para que sus composables y estados paginados
        // entren en el perfil, no sólo la cabecera y el reproductor.
        device.wait(Until.hasObject(By.text("Más vídeos")), 15_000)
        device.findObject(By.text("Más vídeos"))?.click()
        device.waitForIdle()
        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 3, 15)
        device.findObject(By.text("Detalles"))?.click()
        device.waitForIdle()
        device.findObject(By.text("Capítulos"))?.click()
        device.waitForIdle()
        repeat(2) {
            device.swipe(device.displayWidth / 2, device.displayHeight / 3, device.displayWidth / 2, device.displayHeight * 3 / 4, 15)
            device.waitForIdle()
        }
        device.wait(Until.hasObject(By.textContains("Reproducir")), 10_000)
        device.findObject(By.textContains("Reproducir"))?.click()
        device.wait(Until.hasObject(By.res(PACKAGE, "player_settings")), 15_000)
        Thread.sleep(6_000)
        device.pressBack()
        device.waitForIdle()
    }

    private companion object {
        const val PACKAGE = "es.openrtve"
        const val PROGRAM_DEEP_LINK = "https://www.rtve.es/play/videos/la-promesa/"
    }
}
