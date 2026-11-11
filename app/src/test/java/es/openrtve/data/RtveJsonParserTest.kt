package es.openrtve.data

import es.openrtve.domain.ContentKind
import es.openrtve.domain.LinkKind
import es.openrtve.domain.RowLayout
import es.openrtve.domain.isMovie
import es.openrtve.domain.MediaForm
import es.openrtve.domain.mediaForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtveJsonParserTest {
    private val parser = RtveJsonParser()

    @Test
    fun `home rows are sorted, get stable ids and unknown presentations are preserved`() {
        val result = parser.parseHome(
            """
            {
              "id": "16",
              "title": "Portada",
              "rows": [
                {"title":"Dos","orden":2,"moduleType":"Future","tipo":"Nuevo"},
                {"title":"Uno","orden":1,"moduleType":"Collection","urlContent":"https://api.rtve.es/api/collection/1.json"},
                {"title":"Ajena","orden":3,"urlContent":"https://evil.example.org/collection/1.json"},
                {"title":"Enlaces vacíos","orden":4,"tipo":"links","urlContent":"https://www.rtve.es/api/tematicas/1/links.json"},
                {"title":"Enlaces","orden":5,"tipo":"links","urlContent":"https://www.rtve.es/api/tematicas/1/links.json","links":[
                  {"title":"Series para maratón","tipo":"collection","url":"https://api.rtve.es/api/collection/1900.json","image":"https://img.rtve.es/i/1.jpg","imgHorizontal":"https://img.rtve.es/i/1h.jpg"},
                  {"title":"Playz","tipo":"portadaPlay","url":"https://api.rtve.es/play/playz/index_apps.json","image":"https://img.rtve.es/i/2.jpg"},
                  {"title":"Un programa","tipo":"programaPlay","url":"https://api.rtve.es/api/programas/1234.json"},
                  {"title":"Web","tipo":"enlaceExterno","url":"https://www.rtve.es/play/"},
                  {"title":"Ajeno","tipo":"collection","url":"https://evil.example.org/collection/1.json"}
                ]}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf("Uno", "Dos", "Ajena", "Enlaces"), result.rows.map { it.title })
        assertEquals(listOf(0, 1, 2, 3), result.rows.map { it.id })
        assertEquals("Future", result.rows[1].moduleType)
        assertEquals("Nuevo", result.rows[1].presentation)
        assertNull("las URLs fuera de la allowlist se descartan", result.rows[2].contentUrl)

        // Los enlaces vienen en la propia fila; su `urlContent` (enlaces web) no se usa. Los externos y ajenos se omiten.
        val links = result.rows[3].links
        assertNull(result.rows[3].contentUrl)
        assertEquals(listOf("Series para maratón", "Playz", "Un programa"), links.map { it.title })
        assertEquals(listOf(LinkKind.COLLECTION, LinkKind.PORTADA, LinkKind.PROGRAM), links.map { it.kind })
        assertEquals("https://img.rtve.es/i/1h.jpg", links[0].imageUrl)
        assertEquals("https://img.rtve.es/i/2.jpg", links[1].imageUrl)
        assertEquals("1234", links[2].apiId)
    }

    @Test
    fun `row presentation maps to a layout`() {
        val result = parser.parseHome(
            """{"rows":[
              {"title":"a","orden":1,"tipo":"ColeccionSuperDestacado"},
              {"title":"b","orden":2,"tipo":"ColeccionPoster"},
              {"title":"c","orden":3,"tipo":"ColeccionCuadradoPeq"},
              {"title":"d","orden":4,"tipo":"directosTV"},
              {"title":"e","orden":5,"tipo":"AlgoNuevo"},
              {"title":"f","orden":6,"tipo":"ColeccionSuper"},
              {"title":"g","orden":7,"tipo":"programas"},
              {"title":"h","orden":8,"tipo":"ColleccionTops"},
              {"title":"i","orden":9,"tipo":"ColeccionDestacado"}
            ]}""",
        )

        assertEquals(
            listOf(RowLayout.FEATURED, RowLayout.POSTER, RowLayout.SQUARE, RowLayout.LANDSCAPE, RowLayout.LANDSCAPE, RowLayout.POSTER_TALL, RowLayout.POSTER, RowLayout.RANKED, RowLayout.HERO),
            result.rows.map { it.layout },
        )
    }

    @Test
    fun `search results are split into programs and videos`() {
        val results = parser.parseSearch(
            """{
              "programs":{"page":{"items":[{"id":"1726","contentType":"programa","title":"Telediario Internacional"}]}},
              "videos":{"page":{"items":[{"id":"1","contentType":"video","title":"Uno"},{"id":"1","contentType":"video","title":"Uno"}]}}
            }""",
        )

        assertEquals(listOf("1726"), results.programs.map { it.id })
        assertEquals(ContentKind.PROGRAM, results.programs.single().kind)
        assertEquals(1, results.videos.size)
    }

    @Test
    fun `quick filters keep only allowed urls`() {
        val filters = parser.parseQuickFilters(
            """{"items":[
              {"nav-pag_name":"MÁS BUSCADOS","nav-pag_url":"https://recomsys.rtve.es/recommendation/tops?recoId=x"},
              {"nav-pag_name":"AJENO","nav-pag_url":"https://example.org/x"}
            ]}""",
        )

        assertEquals(listOf("MÁS BUSCADOS"), filters.map { it.title })
    }

    @Test
    fun `explore keeps public portadas from the television menu only`() {
        val groups = parser.parseExplore(
            """{
              "television":{"menuBloques":[
                {"title":"Bloque de contenidos","menuItems":[
                  {"title":"DIRECTOS","tipo":"directos"},
                  {"title":"CINE","tipo":"portada","imgBackground":"http://img.rtve.es/cine.jpg","urlContent":"https://www.rtve.es/play/cine/index_apps.json","subscriptor":false},
                  {"title":"SERIES","tipo":"portada","urlContent":"https://www.rtve.es/play/playplus/series/index_apps.json","subscriptor":true},
                  {"title":"CLAN","tipo":"intentApp"}
                ]},
                {"title":"Bloque principal","menuItems":[{"title":"MI CUENTA","tipo":"micuenta"}]}
              ]},
              "radio":{"menuBloques":[{"menuItems":[{"title":"PODCAST","tipo":"portada","urlContent":"https://rtve.es/play/radio/podcasts/index_apps.json"}]}]}
            }""",
        )

        // El árbol de radio de la configuración no se expone, como en RTVE Play; el bloque principal no lleva título.
        assertEquals(listOf<String?>(null), groups.map { it.title })
        assertEquals(listOf("CINE"), groups[0].categories.map { it.title })
        assertEquals("https://img.rtve.es/cine.jpg", groups[0].categories.single().imageUrl)
    }

    @Test
    fun `explore turns each submenu into its own group`() {
        val groups = parser.parseExplore(
            """{
              "television":{"menuBloques":[
                {"title":"Bloque de contenidos","menuItems":[
                  {"orden":1,"title":"CLAN","tipo":"intentApp","appAndroid":"com.rtve.clan"},
                  {"orden":1,"title":"APP RNE","tipo":"intentApp","appAndroid":"es.rtve.playradio"},
                  {"orden":0,"title":"TDP","tipo":"portada","urlContent":"https://www.rtve.es/play/teledeporte/index_apps.json"},
                  {"title":"OTRAS TEMÁTICAS","tipo":"submenu","menuItems":[
                    {"title":"COCINA","tipo":"portada","urlContent":"https://www.rtve.es/play/cocina/index_apps.json"},
                    {"title":"SERIES","tipo":"portada","urlContent":"https://www.rtve.es/play/playplus/series/index_apps.json","subscriptor":true}
                  ]},
                  {"title":"CANALES","tipo":"submenu","menuItems":[
                    {"title":"LA 1","tipo":"portada","logo":"https://img.rtve.es/la1.png","urlContent":"https://www.rtve.es/play/la-1/index_apps.json"},
                    {"title":"TELEDEPORTE","tipo":"portada","urlContent":"https://www.rtve.es/play/teledeporte/index_apps.json"}
                  ]},
                  {"title":"CANALES TEMÁTICOS","tipo":"submenu","menuItems":[
                    {"title":"RTVE COCINA","tipo":"portadaCanalTematico","urlContent":"https://api.rtve.es/api/lives/broadcasts/1.json"}
                  ]},
                  {"title":"A-Z","tipo":"buscadorAZ"}
                ]},
                {"title":"Bloque de INFANTIL","infantil":true,"menuItems":[
                  {"title":"JUNIOR","tipo":"portada","urlContent":"https://www.rtve.es/play/clan/edad/junior/index_apps.json"}
                ]},
                {"title":"Bloque apps","menuItems":[{"title":"OTRAS APPS","tipo":"submenu","menuItems":[{"title":"APP CLAN","tipo":"intentApp"}]}]}
              ],"secciones":{"portadaInfantil":{"urlContent":"https://www.rtve.es/play/clan/index_apps.json"}}},
              "radio":{"menuBloques":[{"title":"Bloque de contenidos","menuItems":[
                {"title":"PODCAST","tipo":"portada","urlContent":"https://rtve.es/play/radio/podcasts/index_apps.json"},
                {"title":"TEMÁTICAS","tipo":"submenu","menuItems":[
                  {"title":"MÚSICA","tipo":"portada","urlContent":"https://rtve.es/play/radio/musica/index_apps.json"}
                ]},
                {"title":"EMISORAS","tipo":"submenu","menuItems":[
                  {"title":"RADIO 3","tipo":"portada","urlContent":"https://www.rtve.es/play/radio/radio-3/index_apps.json"}
                ]}
              ]}]}
            }""",
        )

        assertEquals(
            listOf(null, "Otras temáticas", "Canales", "Canales temáticos", null),
            groups.map { it.title },
        )
        // Solo el bloque marcado `infantil` se señala como tal; los nombres internos de bloque no se muestran.
        assertEquals(listOf(false, false, false, false, true), groups.map { it.isKids })
        // Orden del JSON, como la app oficial; el enlace a la app de Clan pasa a ser su portada, el resto de apps se omite.
        // TDP está también en Canales: se queda allí, con los demás canales.
        assertEquals(listOf("CLAN"), groups[0].categories.map { it.title })
        assertEquals("https://www.rtve.es/play/clan/index_apps.json", groups[0].categories[0].contentUrl)
        // Play+ se filtra también dentro de los submenús.
        assertEquals(listOf("COCINA"), groups[1].categories.map { it.title })
        // Los logos no son fondos.
        assertEquals(listOf("LA 1", "TELEDEPORTE"), groups[2].categories.map { it.title })
        assertEquals(null, groups[2].categories.first().imageUrl)
        // Un canal temático es un directo, no una portada.
        assertEquals(listOf("RTVE COCINA"), groups[3].categories.map { it.title })
        assertTrue(groups[3].categories.single().isLive)
        assertEquals("https://api.rtve.es/api/lives/broadcasts/1.json", groups[3].categories.single().contentUrl)
        assertEquals(listOf("JUNIOR"), groups[4].categories.map { it.title })
    }

    @Test
    fun `channels appear only in their group and keep an image, other aliases stay`() {
        val groups = parser.parseExplore(
            """{"television":{"menuBloques":[{"title":"Bloque de contenidos","menuItems":[
              {"title":"CINE","tipo":"portada","urlContent":"https://www.rtve.es/play/cine/index_apps.json"},
              {"title":"70 AÑOS","tipo":"portada","urlContent":"https://www.rtve.es/play/archivo/index_apps.json"},
              {"title":"LA 2 CAT","tipo":"portada","imgBackground":"https://img.rtve.es/css/la2cat.jpg","urlContent":"https://www.rtve.es/play/la-2-cat/index_apps.json"},
              {"title":"OTRAS TEMÁTICAS","tipo":"submenu","menuItems":[
                {"title":"ARCHIVO","tipo":"portada","urlContent":"https://www.rtve.es/play/archivo/index_apps.json"},
                {"title":"PLAYZ","tipo":"portada","urlContent":"https://www.rtve.es/play/playz/index_apps.json"}
              ]},
              {"title":"CANALES","tipo":"submenu","menuItems":[
                {"title":"LA 2 CAT","tipo":"portada","logo":"https://img.rtve.es/rtve.png","urlContent":"https://www.rtve.es/play/la2cat/index_apps.json"},
                {"title":"PLAYZ","tipo":"portada","urlContent":"https://www.rtve.es/play/playz/index_apps.json"}
              ]}
            ]}]}}""",
        )

        assertEquals(listOf(null, "Otras temáticas", "Canales"), groups.map { it.title })
        // La 2 Cat llega con otra URL en el bloque principal: el mismo nombre basta.
        assertEquals(listOf("CINE", "70 AÑOS"), groups[0].categories.map { it.title })
        // "70 años" y "Archivo" llevan al mismo sitio, pero son accesos editoriales distintos.
        assertEquals(listOf("ARCHIVO"), groups[1].categories.map { it.title })
        assertEquals(listOf("LA 2 CAT", "PLAYZ"), groups[2].categories.map { it.title })
        assertEquals("https://img.rtve.es/css/la2cat.jpg", groups[2].categories[0].imageUrl)
    }

    @Test
    fun `collection is flattened and last multimedia becomes playable`() {
        val result = parser.parseModule(
            raw = """
                {
                  "page": {
                    "items": [{
                      "id": 10,
                      "title": "Colección",
                      "collectionItems": [{
                        "id": 135930,
                        "name": "Programa",
                        "contentType": "video",
                        "requireLogged": false,
                        "imgPoster": "http://img.rtve.es/p/135930?imgProgApi=imgPoster",
                        "lastMultimedia": {
                          "id": "17222239",
                          "title": "Último episodio",
                          "contentType": "video",
                          "allowedInCountry": true,
                          "hasDRM": false
                        }
                      }]
                    }]
                  }
                }
            """.trimIndent(),
            fallbackTitle = "Destacados",
        )

        assertEquals(1, result.items.size)
        with(result.items.single()) {
            assertEquals("135930", id)
            assertEquals("17222239", playbackId)
            assertEquals("Programa", title)
            assertEquals("Último episodio", subtitle)
            assertEquals(ContentKind.PROGRAM, kind)
            assertEquals("https://img.rtve.es/p/135930?imgProgApi=imgPortada&w=960", imageUrl)
            assertEquals("https://img.rtve.es/p/135930?imgProgApi=imgPoster&w=480", posterUrl)
            assertTrue(allowedInCountry == true)
            assertFalse(loginRequired)
            assertFalse(drm)
        }
    }

    @Test
    fun `collection title fills in when the home row has none`() {
        val raw = """{"page":{"items":[{"id":1,"title":"Lo más destacado","name":"Play Portada > Destacados","collectionItems":[]}]}}"""

        assertEquals("Lo más destacado", parser.parseModule(raw, fallbackTitle = "").title)
        assertEquals("Fila", parser.parseModule(raw, fallbackTitle = "Fila").title)
    }

    @Test
    fun `program detail exposes seasons and description`() {
        val detail = parser.parseProgram(
            """
            {"page":{"items":[{
              "id":"178291","name":"La amiga estupenda","contentType":"programa",
              "description":"<p>Elena y Lila</p>","emission":"Sábado a las 22:00",
              "imgPortada":"https://img.rtve.es/imagenes/x.jpg",
              "programTypeID":136519,"outOfEmission":false,"isComplete":false,"showFragments":true,
              "productionDate":"01-01-2022 00:00:00","ageRange":"No recomendable para menores de 16 años",
              "generos":[{"generoInf":"Drama"}],"director":"Una Directora | Otro Director",
              "casting":"Actriz Uno | Actor Dos","languageOriginal":"es","country":"ES",
              "seasons":[
                {"shorttitle":"T1","longTitle":"Temporada 1","id":1000013,"orden":1,"numEpisodes":8},
                {"shorttitle":"T2","longTitle":"Temporada 2","id":1000014,"orden":2,"numEpisodes":6},
                {"shorttitle":"T3","longTitle":"Temporada 3","id":1000015,"orden":3,"numEpisodes":0}
              ]
            }]}}
            """.trimIndent(),
        )

        assertEquals("La amiga estupenda", detail.title)
        assertEquals("Sábado a las 22:00", detail.emission)
        assertEquals("https://img.rtve.es/imagenes/x.jpg", detail.imageUrl)
        assertEquals("136519", detail.programTypeId)
        assertTrue(detail.inEmission)
        assertFalse(detail.isComplete)
        assertTrue(detail.showFragments)
        assertEquals("2022", detail.year)
        assertEquals(listOf("Drama"), detail.genres)
        assertEquals("Una Directora, Otro Director", detail.director)
        assertEquals(listOf("Actriz Uno", "Actor Dos"), detail.cast)
        assertEquals("ES", detail.country)
        // En emisión: la temporada más reciente primero; sin episodios no se lista.
        assertEquals(listOf("1000014", "1000013"), detail.seasons.map { it.id })
        assertEquals(6, detail.seasons.first().episodeCount)
        assertEquals(2, detail.seasons.first().order)
    }

    @Test
    fun `program detail preserves the editorial type of a film cycle`() {
        for (key in listOf("programTypeId", "programTypeID")) {
            val detail = parser.parseProgram(
                """{"page":{"items":[{"id":"61150","name":"Somos cine","programType":"Contenedor Películas","$key":137310}]}}""",
            )
            assertEquals("Contenedor Películas", detail.programType)
            assertEquals("137310", detail.programTypeId)
        }
    }

    @Test
    fun `collection titles drop their editorial path`() {
        val module = parser.parseModule(
            raw = """{"page":{"items":[{"title":"Portada En Play > Destacados","collectionItems":[{"id":"7","title":"Vídeo","contentType":"video"}]}]}}""",
            fallbackTitle = "",
        )
        assertEquals("Destacados", module.title)
    }

    @Test
    fun `audios are not part of the video catalog`() {
        val items = parser.parseModule(
            raw = """{"page":{"items":[{"id":"42","title":"Episodio","contentType":"audio"},{"id":"7","title":"Vídeo","contentType":"video"}]}}""",
            fallbackTitle = "",
        ).items

        assertEquals("los audios son de RTVE Audio", listOf("7"), items.map { it.id })
    }

    @Test
    fun `video page keeps pagination and episode metadata`() {
        val page = parser.parseVideoPage(
            """
            {"page":{"number":2,"totalPages":72,"items":[{
              "id":"17222216","title":"Telediario - 21 horas","contentType":"video",
              "duration":3103400,"publicationDate":"11-09-2026 21:00:00","episode":3,"temporada":"Temporada 1","temporadaId":1028870,
              "type":{"id":39816,"name":"Completo"},"subType":{"id":1,"name":"Tráiler"},
              "programInfo":{"id":"169690","title":"La Promesa","showFragments":true,"programTypeId":136519,"programType":"Series Prime Time"},
              "requireLogged":false,"hasDRM":true,"allowedInCountry":true
            }]}}
            """.trimIndent(),
        )

        assertEquals(2, page.page)
        assertEquals(72, page.totalPages)
        assertTrue(page.hasMore)
        with(page.items.single()) {
            assertEquals("17222216", playbackId)
            assertEquals(3103400L, durationMs)
            assertEquals("11-09-2026 21:00:00", publicationDate)
            assertEquals(3, episode)
            assertEquals("Temporada 1", seasonTitle)
            assertEquals("1028870", seasonId)
            assertEquals("39816", mediaTypeId)
            assertEquals("Completo", mediaTypeName)
            assertEquals("Tráiler", mediaSubtypeName)
            assertTrue(programShowsFragments == true)
            assertEquals("136519", programTypeId)
            assertEquals("Series Prime Time", programType)
            assertTrue(drm)
        }
    }

    @Test
    fun `container programs expose the movie inside instead of the container`() {
        val result = parser.parseModule(
            raw = """
                {"page":{"items":[{"id":1,"collectionItems":[
                  {"id":"77590","name":"Cine internacional","contentType":"video","programType":"Contenedor Películas",
                   "lastMultimedia":{"id":"16368062","title":"La favorita","contentType":"video","subType":{"name":"Película"},"previews":{"vertical":"https://img.rtve.es/v.jpg"}}},
                  {"id":"77590","name":"Cine internacional","contentType":"video","programType":"Contenedor Películas",
                   "lastMultimedia":{"id":"17189185","title":"Otra peli","contentType":"video"}}
                ]}]}}
            """.trimIndent(),
            fallbackTitle = "Cine",
        ).items

        assertEquals(listOf("16368062", "17189185"), result.map { it.id })
        with(result[0]) {
            assertEquals(ContentKind.VIDEO, kind)
            assertEquals("La favorita", title)
            assertEquals("Cine internacional", subtitle)
            assertEquals("77590", programId)
            assertEquals("https://img.rtve.es/v/16368062/vertical?w=480", posterUrl)
            assertEquals("https://img.rtve.es/v/16368062/horizontal2?w=960", imageUrl)
        }
    }

    @Test
    fun `a program repeated across a collection means each item is its media`() {
        val result = parser.parseModule(
            raw = """
                {"page":{"items":[{"id":1,"collectionItems":[
                  {"id":"1000","name":"Vuelta a España","contentType":"video","lastMultimedia":{"id":"1","title":"Etapa 1","contentType":"video"}},
                  {"id":"1000","name":"Vuelta a España","contentType":"video","lastMultimedia":{"id":"2","title":"Etapa 2","contentType":"video"}},
                  {"id":"2000","name":"Telediario","contentType":"video","lastMultimedia":{"id":"3","title":"TD 21h","contentType":"video"}},
                  {"id":"n1","title":"Una noticia","contentType":"noticia"}
                ]}]}}
            """.trimIndent(),
            fallbackTitle = "",
        ).items

        assertEquals(listOf("1", "2", "2000"), result.map { it.id })
        assertEquals(listOf(ContentKind.VIDEO, ContentKind.VIDEO, ContentKind.PROGRAM), result.map { it.kind })
        assertEquals("Etapa 1", result[0].title)
        assertEquals("Vuelta a España", result[0].subtitle)
        assertEquals("https://img.rtve.es/v/1/horizontal2?w=960", result[0].imageUrl)
        assertEquals("Telediario", result[2].title)
    }

    @Test
    fun `the explicit program type is not replaced by the subtype of its latest video`() {
        val item = parser.parseModule(
            """{"page":{"items":[{
              "id":"88070","name":"Historia de nuestro cine","contentType":"video",
              "programTypeId":136525,"programType":"Entrevistas",
              "lastMultimedia":{"id":"film","title":"Una película","contentType":"video","subType":{"id":130734,"name":"Película"}}
            }]}}""", "",
        ).items.single()

        assertEquals("88070", item.id)
        assertEquals(ContentKind.PROGRAM, item.kind)
        assertEquals("Historia de nuestro cine", item.title)
    }

    @Test
    fun `a film is identified by its API subtype ID even without a subtype name`() {
        val item = parser.parseVideoPage(
            """{"page":{"items":[{"id":"film","title":"Película","contentType":"video","subType":{"id":130734}}]}}""",
        ).items.single()

        assertEquals("130734", item.mediaSubtypeId)
        assertTrue(item.isMovie)
    }

    @Test
    fun `type and subtype may be API objects numeric strings or names`() {
        val representations = listOf(
            """"type":{"id":39980,"name":"Avance"},"subType":{"id":130734,"name":"Película"}""",
            """"type":"39980","subType":"130734"""",
            """"type":"Fragmento","subType":"Película"""",
        )
        for (fields in representations) {
            val item = parser.parseVideoPage("""{"page":{"items":[{"id":"extra","title":"Vídeo","contentType":"video",$fields}]}}""").items.single()
            assertEquals(MediaForm.EXTRA, item.mediaForm)
            assertTrue(item.isMovie)
        }
    }

    @Test
    fun `an item without its own id is the media it wraps, not a program`() {
        // Visto en "Descubre las novedades de la temporada": solo `contentType` y `lastMultimedia`.
        val result = parser.parseModule(
            raw = """
                {"page":{"items":[{"id":1,"collectionItems":[
                  {"contentType":"video","lastMultimedia":{"id":"17208098","title":"'Flash Moda' o 'Cervantes por el mundo', novedades de La 2","contentType":"video","programInfo":{"id":"40110","title":"Otros programas"}}},
                  {"id":"56811","name":"Flash moda","contentType":"video","lastMultimedia":{"id":"9","title":"Adolfo Domínguez","contentType":"video"}}
                ]}]}}
            """.trimIndent(),
            fallbackTitle = "",
        ).items

        assertEquals(listOf("17208098", "56811"), result.map { it.id })
        assertEquals(listOf(ContentKind.VIDEO, ContentKind.PROGRAM), result.map { it.kind })
        assertEquals("40110", result[0].programId)
        assertEquals("17208098", result[0].playbackId)
    }

    @Test
    fun `live items carry schedule, progress, logo and category`() {
        val items = parser.parseModule(
            raw = """{"page":{"items":[
              {"id":"80745","tipo":"peticion","titulo":"Etapa 21","antetitulo":"LA VUELTA 2026","live":false,"inicio":"13-09-2026 16:10:00","duracion":235,"porcentaje":0,"idAsset":"6712432","logo":"https://css.rtve.es/css/logo.png"},
              {"id":"1","tipo":"broadcast","titulo":"VIAJE AL CENTRO DE LA TELE","live":true,"inicio":"13-09-2026 11:05:00","duracion":60,"porcentaje":50,"idAsset":"1688877"}
            ]}}""",
            fallbackTitle = "Directos",
        ).items

        val upcoming = items[0].live!!
        assertFalse(upcoming.isOnAir)
        assertEquals("La Vuelta 2026", upcoming.category)
        assertEquals("La Vuelta 2026", items[0].subtitle)
        assertEquals("https://css.rtve.es/css/logo.png", upcoming.channelLogoUrl)
        // 16:10 en Madrid (CEST, UTC+2) = 14:10 UTC.
        assertEquals(1789308600000L, upcoming.startsAtMillis)
        assertTrue(upcoming.isUpcomingAt(1789308000000L))
        assertFalse(upcoming.isUpcomingAt(1789309000000L))

        val onAir = items[1].live!!
        assertTrue(onAir.isOnAir)
        // 11:05 Madrid + 30 min de 60 = 50 %.
        assertEquals(0.5f, onAir.progressAt(1789290300000L + 30 * 60_000L)!!, 0.01f)
    }

    @Test
    fun `fast channels without title get one from description or permalink`() {
        val result = parser.parseModule(
            raw = """{"page":{"items":[
              {"id":"21007","idAsset":"1","tipo":"broadcast","contentType":"directo","descripcion":"Canal RTVE La Promesa: disfruta de capítulos"},
              {"id":"24156","idAsset":"2","tipo":"broadcast","contentType":"directo","permalink":"play-cuentame"}
            ]}}""",
            fallbackTitle = "",
        ).items

        assertEquals(listOf("Canal RTVE La Promesa", "Play Cuentame"), result.map { it.title })
    }

    @Test
    fun `non catalog rows are dropped from portadas`() {
        val rows = parser.parseHome(
            """{"rows":[{"title":"a","orden":1,"tipo":"noticias"},{"title":"b","orden":2,"tipo":"Parrilla"},{"title":"c","orden":3,"tipo":"videos"}]}""",
        ).rows

        assertEquals(listOf("c"), rows.map { it.title })
    }

    @Test
    fun `videos and programs without images fall back to the image service`() {
        val items = parser.parseModule(
            raw = """{"page":{"items":[{"id":"1","title":"v","contentType":"video"},{"id":"2","name":"p","contentType":"programa"}]}}""",
            fallbackTitle = "",
        ).items

        assertEquals("https://img.rtve.es/v/1/horizontal2?w=960", items[0].imageUrl)
        assertEquals("https://img.rtve.es/v/1/vertical?w=480", items[0].posterUrl)
        assertEquals("https://img.rtve.es/p/2?imgProgApi=imgPoster&w=480", items[1].posterUrl)
        assertEquals("https://img.rtve.es/p/2?imgProgApi=imgBackground&w=480", items[1].squareUrl)
        // ColeccionSuper usa otro recorte del programa (imgCol); los vídeos no lo tienen.
        assertEquals("https://img.rtve.es/p/2?imgProgApi=imgCol&w=480", items[1].tallPosterUrl)
        assertNull(items[0].tallPosterUrl)
    }

    @Test
    fun `video detail collects the movie sheet`() {
        val detail = parser.parseVideo(
            """
            {"page":{"items":[{
              "id":"16368062","title":"La favorita","contentType":"video","duration":6493100,
              "description":"<p>Reina Ana</p>","promoDesc":"Comedia negra","subType":{"id":1,"name":"Película"},
              "previews":{"horizontal":null,"horizontal2":"https://img.rtve.es/h2.jpg","vertical":"https://img.rtve.es/v.jpg"},
              "casting":"Emma Stone | Olivia Colman","director":"Yorgos Lanthimos",
              "generos":[{"generoInf":"Drama","subGeneroInf":"Drama"},{"generoInf":"Comedia"}],
              "productionDate":"2018","ageRange":"No recomendable para menores de 12 años","languageOriginal":"en",
              "expirationDate":"19-09-2026 02:40:00","htmlUrl":"https://www.rtve.es/play/videos/cine/la-favorita/",
              "programInfo":{"id":"77590","title":"Cine internacional"},"hasDRM":true,"requireLogged":true,"allowedInCountry":true
            }]}}
            """.trimIndent(),
        )

        assertEquals("La favorita", detail.item.title)
        assertEquals("16368062", detail.item.playbackId)
        assertEquals("77590", detail.item.programId)
        assertEquals("Cine internacional", detail.programTitle)
        assertEquals("https://img.rtve.es/h2.jpg", detail.backdropUrl)
        assertEquals("https://img.rtve.es/v/16368062/vertical?w=480", detail.item.posterUrl)
        assertEquals(listOf("Emma Stone", "Olivia Colman"), detail.cast)
        assertEquals(listOf("Drama", "Comedia"), detail.genres)
        assertEquals("2018", detail.year)
        assertEquals("Película", detail.item.mediaSubtypeName)
        assertEquals("Comedia negra", detail.promo)
        assertTrue(detail.item.drm)
    }

    @Test
    fun `program without last multimedia has no playback id`() {
        val result = parser.parseModule(
            raw = """{"page":{"items":[{"id":"55","title":"Serie","contentType":"programa"}]}}""",
            fallbackTitle = "Series",
        ).items.single()

        assertEquals(ContentKind.PROGRAM, result.kind)
        assertNull(result.playbackId)
    }

    @Test
    fun `declared content type wins over the presence of idAsset`() {
        val result = parser.parseModule(
            raw = """
                {"page":{"items":[
                  {"id":"1","title":"VOD","contentType":"video","idAsset":"1"},
                  {"id":"2","title":"Canal","idAsset":"la1"},
                  {"id":"3","title":"Evento","contentType":"video","tipo":"broadcast","idAsset":"ev"}
                ]}}
            """.trimIndent(),
            fallbackTitle = "Mixto",
        ).items

        assertEquals(listOf(ContentKind.VIDEO, ContentKind.LIVE, ContentKind.LIVE), result.map { it.kind })
    }

    @Test
    fun `duplicated items are collapsed so list keys stay unique`() {
        val result = parser.parseModule(
            raw = """{"page":{"items":[{"id":"7","title":"x","contentType":"video"},{"id":"7","title":"x","contentType":"video"}]}}""",
            fallbackTitle = "Repetidos",
        )

        assertEquals(1, result.items.size)
    }

    @Test
    fun `images are picked by aspect ratio and foreign hosts are dropped`() {
        val result = parser.parseModule(
            raw = """
                {"page":{"items":[{
                  "id":"9","titulo":"Canal","tipo":"broadcast","idAsset":"a9",
                  "thumb":"https://img.rtve.es/thumbs/9.jpg","thumb_square":"https://img.rtve.es/thumbs/9_sq.jpg","thumb_vertical":"https://img.rtve.es/thumbs/9_v.jpg"
                },{
                  "id":"10","titulo":"Ajeno","tipo":"broadcast","idAsset":"a10",
                  "thumb":"https://cdn.example.org/10.jpg"
                }]}}
            """.trimIndent(),
            fallbackTitle = "Directos",
        ).items

        assertEquals("https://img.rtve.es/thumbs/9.jpg", result[0].imageUrl)
        assertEquals("https://img.rtve.es/thumbs/9_sq.jpg", result[0].squareUrl)
        assertEquals("https://img.rtve.es/thumbs/9_v.jpg", result[0].posterUrl)
        assertNull(result[1].imageUrl)
    }
}
