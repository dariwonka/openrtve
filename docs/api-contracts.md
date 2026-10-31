# Contratos HTTP y modelo canónico

## Convenciones

Este catálogo describe las interfaces que consume RTVE Play 8.8.1 y que han de
quedar detrás de adaptadores propios. No es una promesa de estabilidad de RTVE.
Las URLs devueltas por la configuración remota prevalecen sobre las plantillas
documentadas y solo se aceptarán hosts de una allowlist explícita.

Estado de evidencia:

- `HTTP`: respuesta pública verificada el 12-09-2026.
- `APK`: llamada y modelo observados en la versión 8.8.1.
- `AUTH`: requiere una sesión legítima; solo se verificó estáticamente.

Cabecera observada en las interfaces Retrofit:

```http
User-Agent: App-RtvePlay-Android
```

No es necesaria para identificar nuestro cliente y no se debe falsificar. La
implementación nueva enviará su propio User-Agent.

## Sobre paginado común

La mayoría de recursos de `api.rtve.es` y `api2.rtve.es` usan:

```json
{
  "page": {
    "items": [],
    "number": 1,
    "size": 20,
    "offset": 0,
    "total": 0,
    "totalPages": 0,
    "numElements": 0
  },
  "query": "/ruta/relativa?filtros=..."
}
```

`query` solo aparece en algunos contratos, especialmente búsqueda. La API usa
páginas con base 1 en las muestras. El cliente debe seguir `totalPages`, aceptar
arrays vacíos y no inferir fin únicamente por `items.size < size`.

Contrato interno sugerido:

```kotlin
data class Page<T>(
    val items: List<T>,
    val page: Int,
    val pageSize: Int,
    val offset: Int?,
    val totalItems: Int?,
    val totalPages: Int?,
    val nextQuery: String?,
)
```

## Configuración y portadas

| Operación | Método y plantilla | Evidencia | Resultado |
|---|---|---|---|
| Configuración | `GET https://www.rtve.es/m/configs/rtve_play/2026/estructura2.json` | HTTP/APK | Flags, menús, portadas, fuentes, parrillas, anuncios y versión |
| Locale | `GET https://includesweb.rtve.es/locale.json` | APK | Código de localización; fallback a locale del dispositivo |
| Portada TV | `GET https://www.rtve.es/play/index_apps.json` | HTTP | `CustomHome` con `rows` |
| Portada radio | `GET https://www.rtve.es/play/radio/index_apps.json` | HTTP | `CustomHome` con `rows` |
| Portada temática | URL `index_apps.json` entregada por configuración | HTTP/APK | Mismo contrato de filas |
| Promociones | URL entregada por `promociones.feedPromociones` | APK | Lista de promociones vigentes |
| Promos de pausa | URL entregada por `feedPromoPause` | APK | Lista de promos para pausa |

DTO mínimo para portada:

```kotlin
data class HomeManifestDto(
    val id: String?,
    val title: String?,
    val rows: List<HomeRowDto> = emptyList(),
)

data class HomeRowDto(
    val title: String?,
    val subTitle: String?,
    val order: Int?,          // JSON: orden
    val moduleType: String?,
    val presentation: String?, // JSON: tipo
    val contentUrl: String?,  // JSON: urlContent
    val layoutCount: Int?,    // JSON: numLayout
    val hideLabels: Boolean?, // JSON: noLabels
)
```

El parser debe conservar `moduleType` y `tipo` desconocidos como valores raw.

La versión analizada conoce, al menos, estas presentaciones:

```text
MiRtve, ColeccionSuperDestacado, favoritos, ColeccionSuper, audios,
moduloNowNextCanal, programas, shorts, videos, moduloDirectoRadio,
ColeccionCuadrado, ColleccionTops, Tops, links, benidorm26,
ColeccionApaisado, directosTV, LogoRadio, videoPoster, seguirviendo,
ColeccionDestacado, directosTV16, StoriesPoster, Parrilla,
ColeccionPoster, recomendaciones, ColeccionCuadradoPeq
```

Una coincidencia especial de `urlContent` con el feed de directos puede
prevalecer sobre `tipo`. El dispatch recomendado es primero por `moduleType` y
fuente, después por presentación; ninguno de los dos campos es un enum cerrado.

## Catálogo público

Base habitual: `https://api.rtve.es/api/`. La configuración mezcla también
`api2.rtve.es` y `www.rtve.es/api`; los adaptadores deben aceptar URLs absolutas
validadas.

| Recurso | Método y ruta | Evidencia | Notas |
|---|---|---|---|
| Colección | `GET collection/{id}.json` | HTTP/APK | El item exterior contiene `collectionItems` |
| Programa | `GET programas/{id}.json` | HTTP/APK | Referencias a temporadas, vídeos, audios y relacionados |
| Vídeos de programa | `GET programas/{id}/videos.json` | HTTP/APK | Capítulos: `type=39816`; «Más vídeos»: `type=39978,39980`; ambos con `page` y `order` |
| Vídeos de temporada | `GET programas/{id}/temporadas/{seasonId}/videos.json` | APK | Contenido completo con `type=39816` |
| Audios de programa | `GET programas/{id}/audios.json` | HTTP/APK | `type`, `page` |
| Multimedias de programa | `GET programas/{id}/multimedias.json` | APK | `type=39816`, `page`, `order` |
| Noticias de temática | `GET tematicas/{id}/noticias.json` | APK | Paginado |
| Relacionados | URL de configuración/recomendación | APK | Diferentes criterios para programa, documental y cine |
| Siguiente vídeo | `GET videos/{id}/next.json` | APK | Respuesta paginada, normalmente un item |
| Vídeo | `GET videos/{id}.json` | HTTP/APK | Metadatos, derechos, qualities y relaciones |
| Audio | `GET audios/{id}.json` | HTTP/APK | Metadatos y qualities MP3 |
| Directo por asset | `GET lives/{idAsset}.json` | HTTP/APK | Normalmente un item `broadcast` |
| Multimedia genérico | `GET multimedias/{id}.json` | APK | Permite distinguir audio/vídeo |
| Noticia | `GET noticias/{id}.json` | APK | Detalle de noticia |
| Destacado de noticia | `GET noticias/{id}/multimedias/destacado.json` | APK | Vídeo, audio o imagen |
| Imagen | `GET imagenes/{id}.json` | APK | Detalle de imagen |
| Fotogalería | `GET fotogalerias/{id}/multimedias.json` | APK | Lista multimedia |

Filtros de tipo observados:

| ID | Uso |
|---:|---|
| 39816 | Completo |
| 39978 | Fragmento |
| 39979 | Variante de fragmento |
| 39980 | Avance (incluye contenidos con subtipo Tráiler) |
| 39981 | Variante de fragmento |

No deben convertirse en un enum cerrado: el backend puede añadir IDs.

## Directos

| Operación | Método y ruta | Evidencia |
|---|---|---|
| Grupos de portada | `GET lives/pagina-portada.json` | HTTP/APK |
| Grupo | `GET lives/agr-directos/{groupId}.json` | HTTP/APK |
| Items de grupo | `GET lives/agr-directos/{groupId}/directos.json` | HTTP/APK |
| Evento a petición | `GET lives/peticiones/{requestId}.json` | APK |
| Canal/broadcast | `GET lives/broadcasts/{broadcastId}.json` | HTTP indirecto/APK |
| Detalle por asset | `GET lives/{idAsset}.json` | HTTP/APK |

DTO normalizado sugerido:

```kotlin
sealed interface LiveSource {
    val id: String
    val assetId: String?
    val title: String?
    val isLive: Boolean?
    val drm: Boolean?
    val loginRequired: Boolean?

    data class Channel(/* ... */) : LiveSource
    data class Event(/* ... */) : LiveSource
}

data class StartOverData(
    val golumiPath: String?,
    val sgce: String?,
    val before: ScheduleSlot?,
    val now: ScheduleSlot?,
    val next: ScheduleSlot?,
)
```

`id` identifica el registro editorial; `idAsset` identifica el stream. No son
intercambiables.

Para un item `peticion`, el lanzamiento consulta
`GET multimedias/{idAsset}.json`: `contentType=audio` selecciona el servicio de
audio y cualquier resultado distinto o ausente degrada al reproductor de
vídeo. `requireLogged` se evalúa antes de iniciar la reproducción.

`liveLocale` de la configuración es una lista de assets (admite `*`) sometidos
a comprobación del reloj: la app rechaza esos items cuando la diferencia entre
hora local y hora de Madrid alcanza tres horas. Debe modelarse como una regla
de disponibilidad separada de región/geo.

## Parrilla y territorio

| Operación | Plantilla | Evidencia | Forma |
|---|---|---|---|
| Parrilla TV | URL de configuración, normalmente `GET /api/schedule/{channel}.json` | HTTP/APK | `diahoy`, canal, asset, índice actual, `items` |
| Parrilla radio semanal | `GET /servicios/programasRadio/{station}.json?emision=semana` | HTTP/APK | `emisiones` y días |
| Radio ahora/siguiente | `GET /servicios/programasRadio/{station}.json?emision=ahora-sig` | HTTP/APK | emisora + programas |
| Emisoras territoriales | URL `radio.secciones.territoriales.emisoras` | APK | Lista de emisoras |
| Informativos territoriales | URL `radio.secciones.territoriales.informativos` | APK | Colección |
| Directos territoriales TV | URL `desconexionTerritorial.urlDesconexiones` | APK | Lista de IDs y títulos |
| Región | URL `desconexionTerritorial.urlLocalizacion` | APK | Geo regional |

Los códigos de canal y estación se obtienen de configuración; no debe existir
una lista compilada como única fuente de verdad.

## Búsqueda

Petición inicial:

```http
GET https://api.rtve.es/api/search/results
    ?search={texto-URL-encoded}
    &context={tve|rne|clan}
    &tipology={video|audio}
    &type=completo
```

Perfil infantil:

```text
&isChild=true&genre=96150
```

Respuesta:

```kotlin
data class SearchResponseDto(
    val programs: ApiEnvelopeDto<RemoteItemDto>?,
    val videos: ApiEnvelopeDto<RemoteItemDto>?,
    val audios: ApiEnvelopeDto<RemoteItemDto>?,
)
```

Para páginas posteriores, se concatena la base de búsqueda con el `query`
relativo de cada bloque y se añade `page={n}`. Antes de hacerlo hay que validar
que `query` comience por `/` y no cambie de host.

La configuración añade feeds de búsquedas predefinidas, términos populares y
recomendaciones por género. Se consideran módulos editoriales, no parte del
motor de búsqueda.

## Contrato remoto heterogéneo

### Tres capas para organizar el catálogo

La estructura de una ficha depende de `programTypeId`/`programType`: un
`Contenedor Películas` (`137310`) muestra un catálogo; un programa conserva sus
emisiones y temporadas. Cada vídeo se clasifica después por `type` y `subType`,
sin usar su título para asignarle una categoría:

| Capa | Valores | Uso |
|---|---|---|
| Forma (`type`) | `39816` completo; `39978` fragmento; `39980` avance | Separar completos de «Más vídeos» |
| Subtipo (`subType`) | `130734` película; `130735` documental; ausente u otro | Separar las piezas completas de un catálogo |
| Programa padre | Contenedor o programa con emisiones/temporadas | Mantener los capítulos de una serie documental como capítulos |

Un completo no implica una película: la primera página de Somos cine contiene
también un documental y un vídeo sin subtipo (verificado el 31-10-2026). Las
películas tienen sección y ficha propias incluso dentro de un programa como
Historia de nuestro cine. Los extras conservan su forma aunque su subtipo sea
película; no reciben capítulos ni recomendaciones de películas por ese motivo.
Un título que diga «Tráiler» no basta para reclasificarlo.

Las secciones comparten las páginas originales de completos y extras. Una página
sin películas no termina el catálogo de películas: el scroll se reevalúa al
avanzar la página, aunque no haya aumentado el número de tarjetas de esa sección.

### Variantes de campos

Campos observados en los items se agrupan así:

| Grupo | Campos representativos |
|---|---|
| Identidad | `id`, `uid`, `idAsset`, `idPrograma`, `idGolumi`, `sgce`, `uri`, `permalink` |
| Tipo | `contentType`, `type`, `subType`, `tipo`, `consumption`, `live` |
| Texto | `title`, `name`, `titulo`, `shortTitle`, `longTitle`, `description`, `promoText` |
| Programa | `programRef`, `programInfo`, `seasons`, `temporadaId`, `episode`, `lastMultimedia` |
| Derechos | `allowedInCountry`, `requireLogged`, `paidContent`, `hasDRM`, `notDownloadable`, `expirationDate` |
| Accesibilidad | `subtitleRef`, `audioOriginal`, `audioDescription`, `descriptors`, `language` |
| Imagen | `imageSEO`, `imgBackground*`, `imgPoster*`, `imgPortada*`, `imgCol*`, `imgBanner*`, `thumb*`, `previews` |
| Emisión | `inicio`, `fin`, `begintime`, `hora`, `horaFin`, `dateOfEmission`, `before`, `now`, `next` |
| Progreso | `mediaStatus.progress`, `mediaStatus.v`, `porcentaje`, `isComplete` |
| Relaciones | `videosRef`, `audiosRef`, `temporadasRef`, `relacionadosRef`, `collectionItems` |

Inconsistencias verificadas:

- `type` es unas veces `{id,name}` y otras un string;
- `subType.id` puede ser `null`;
- IDs aparecen como string o número;
- booleanos y números pueden faltar;
- una colección introduce un nivel extra: `page.items[0].collectionItems`;
- descripciones contienen HTML;
- `allowedInCountry` puede ser booleano o faltar;
- `contentType` usa `program`, `programa`, `video`, `audio` y `directo`.

Adaptador recomendado:

```kotlin
@JvmInline value class ContentId(val value: String)

sealed interface Content {
    val id: ContentId
    data class Program(/* ... */) : Content
    data class Video(/* ... */) : Content
    data class Audio(/* ... */) : Content
    data class Live(/* ... */) : Content
    data class News(/* ... */) : Content
    data class Image(/* ... */) : Content
    data class Unknown(val rawType: String?, override val id: ContentId) : Content
}

data class Rights(
    val geo: Availability,
    val loginRequired: Boolean,
    val paid: Boolean,
    val drm: Boolean,
    val downloadable: Boolean,
    val expiresAt: Instant?,
)
```

`Availability` debe tener `Allowed`, `Denied` y `Unknown`; convertir un campo
ausente en “permitido” sería inseguro.

## Resolución de streams

### Vídeo VOD

| Ruta | Plantilla | Condición observada |
|---|---|---|
| DASH principal | `https://ztnr.rtve.es/ztnr/{id}.mpd` | Camino directo actual/DRM |
| DASH TV/compatibilidad | `https://ztnr.rtve.es/ztnr/tv/{id}.mpd` | Android antiguo o Widevine según flag `vw` |
| Consumer heredado | `/ztnr/consumer/{consumer}/video/{token}` → redirección | VOD no DRM y migraciones |
| Offline | `https://ztnr.rtve.es/ztnr/{id}.mpd?offlineVod=true` | Descarga |

Resolución clean-room:

```kotlin
data class PlaybackRequest(
    val contentId: ContentId,
    val kind: MediaKind,
    val rights: Rights,
    val startOver: Boolean = false,
    val lowData: Boolean = false,
)

data class ResolvedMedia(
    val uri: Uri,
    val mimeType: String?,
    val drm: DrmSpec?,
    val ads: AdSpec?,
    val isLive: Boolean,
    val fallbackUris: List<Uri> = emptyList(),
)
```

El token consumer no se documenta con sus claves. El adaptador heredado exige
un firmante autorizado. Véase [reverse-engineering-ztnr.md](reverse-engineering-ztnr.md).

### Directo

| Orden lógico | Plantilla |
|---:|---|
| 1 | MPD DRM si `hasDRM` |
| 2 | MPD si lo habilita configuración |
| 3 | `https://ztnr.rtve.es/ztnr/{idAsset}.m3u8` |
| 4 | `https://ztnr.rtve.es/ztnr/tv/{idAsset}.m3u8` |

El error Media3 `ERROR_CODE_BEHIND_LIVE_WINDOW` debe reconstruir el item y
reiniciar en el borde en directo. HTTP 403 se normaliza a `GeoBlocked` o
`LicenseDenied` según la fase.

### Start-over

Base pública y base Play+ proceden de configuración. Composición:

```text
base + idGolumi + [sgce] + suffix
```

Sufijos observados: `.mpd`, `_dvr.mpd`, `dvr.mpd`, `_drm.mpd`, `drm.mpd`,
`_dvr_drm.mpd`, `dvr_drm.mpd`. Primero puede probarse la variante exacta con
`sgce`; solo un 200 la confirma. No se deben hacer sondeos a hosts que no estén
en allowlist.

### Audio

| Ruta | Uso |
|---|---|
| `https://ztnr.rtve.es/ztnr/{id}.mp3` | Calidad pública devuelta por el detalle de audio |
| Consumer `PLAY_RADIO` → 301/302 | Audio VOD/directo heredado |
| Consumer Cast audio → 301/302 | Cast heredado |

La URL de `qualities.filePath` tiene prioridad si devuelve un tipo compatible y
la política de derechos la permite.

### Contrato de cola Cast

Para VOD, el adaptador genera `MediaQueueItem[]` desde la playlist, identifica
la posición del item solicitado y carga la cola con posición inicial de
reanudación. Cada entrada usa stream buffered, entity igual al ID, metadata de
título/programa/imagen y MIME HLS o DASH según la resolución. El autoplay puede
suprimirse para excepciones editoriales; los items incluidos en la cola sí se
marcan reproducibles en secuencia.

Los medios DRM entregan al receptor datos de licencia como `customData`; los
Play+ autorizados pueden requerir parámetros de sesión en la URL resuelta.
Ambos son datos efímeros y sensibles: no forman parte del modelo persistente,
fixtures ni logs. Antes de `queueLoad` se vuelven a evaluar país, login y
suscripción, y una sesión activa refresca su token.

### Semántica HTTP verificada de las rutas directas

El 12-09-2026 se sondaron un MPD VOD, un HLS live y un MP3 de audio sin seguir
la redirección y sin descargar el contenido. En los tres casos:

- `HEAD` devolvió 403;
- `GET` limitado a un byte devolvió 302;
- el MIME anunciado fue respectivamente DASH, MPEG-URL y MP3.

Por tanto, las rutas “directas” son también puntos de resolución que entregan
un `Location` temporal. Media3 puede seguir la redirección; las comprobaciones
de salud deben usar un GET mínimo y no interpretar el rechazo de HEAD como
indisponibilidad. La URL temporal no se registró ni forma parte del contrato.

### Hallazgos del 12-09-2026 sobre derechos

Sondeos con `GET` de un byte, sin seguir la redirección ni descargar contenido:

- `requireLogged` **no se aplica en servidor**: `ztnr/{id}.mpd`, `ztnr/{idAsset}.m3u8`
  y `ztnr/{idAsset}.mpd` devuelven 302 con `Location` para items marcados con
  `requireLogged=true` (VOD y directos) sin ninguna credencial. Es una puerta
  del cliente móvil oficial; el cliente de TV no la aplica. OpenRTVE la trata
  como política opcional (`PlaybackResolver(enforceLoginGate)`), desactivada.
- `GET api/token/{id}` responde 200 **sin autenticación** para VOD y directos,
  con `token`, `widevineURL` (Axinom, mensaje de derechos en la query),
  `fairplayURL` y `fairplayCert`. Para items sin DRM también responde 200.
- El MPD de `ztnr/{id}.mpd` de un contenido protegido lleva
  `ContentProtection` Widevine con `cenc:default_KID`; el de `ztnr/tv/{id}.mpd`
  apunta a una variante **sin** protección. Es la ruta de compatibilidad para
  dispositivos sin Widevine; OpenRTVE la usa solo como fallback.
- El HLS de un directo con DRM usa `SAMPLE-AES` + `skd://` (FairPlay): en
  Android hay que usar el MPD.

### Orden de los episodios (verificado el 18-09-2026)

- `programas/{id}/videos.json` y `programas/{id}/temporadas/{sid}/videos.json`
  aceptan `order=multimedia_date_emission,asc|desc` (sin él, `desc`). La app
  oficial lo decide en `ProgramaFragmentOrderUtils.getOrderForVideos` a partir
  de `programTypeID`, `outOfEmission` e `isComplete` del programa:
  `isComplete` → `asc`; en emisión y tipo 132534/136522 (Noticias, Especial
  Evento) → `desc`; tipo 136613/136525/136521/137650 (Conciertos, Entrevistas,
  Reportajes Factual, Documental) → `desc`; en emisión y primera pestaña de
  temporada (o lista completa) → `desc`; el resto → `asc`. OpenRTVE lo replica
  en `ProgramDetail.episodeOrder`.
- Las temporadas salen de `seasons` del programa (las de `numEpisodes` 0 no se
  listan), ordenadas por `orden` en local: descendente si el programa emite, está
  completo o es de los tipos "siempre `desc`"; ascendente si terminó sin
  completar. `temporadas.json` ignora `order`.
- Un item de colección sin `id` propio y con `lastMultimedia` (visto en
  "Descubre las novedades de la temporada") es ese medio, no un programa.
- Las fichas mantienen separados los completos (`39816`) en «Capítulos» y los
  fragmentos/avances (`39978,39980`) en «Más vídeos». El endpoint acepta ambos
  IDs separados por coma y pagina el conjunto; `relacionados.json` no se usa
  para esa pestaña porque contiene principalmente otros programas.

### Radio (verificado el 13-09-2026)

- Los episodios de un programa de radio están en `programas/{id}/audios.json`
  (`videos.json` solo devuelve videopódcasts). El programa se reconoce por
  `mainTopic` con prefijo `Radio/` o `htmlUrl` con `/audios/`.
- El módulo "En directo" de la portada de radio (`moduleType=moduloDirectoRadio`)
  llega sin `urlContent`; la fuente es `radio.secciones.directosRadio.urlContent`
  de la configuración. `api2.rtve.es/api/lives/agr-directos/29/directos.json`
  responde 301 a una URL `http://`: hay que elevarla a HTTPS.
- Las emisoras llevan `audio: true`; su stream es HLS (`ztnr/{idAsset}.m3u8`),
  así que el MIME no distingue audio de vídeo.
- `audios/{id}.json` tiene la misma forma que `videos/{id}.json`.

## DRM y descargas

| Operación | Contrato | Evidencia |
|---|---|---|
| Descubrir licencia | `GET https://api.rtve.es/api/token/{idAsset}` | APK |
| Reproducir | Widevine UUID + `widevineURL` | APK |
| Descargar | MPD `offlineVod=true` + `OfflineLicenseHelper.downloadLicense` | APK |
| Renovar | comprobar segundos restantes y volver a adquirir online | APK |
| Reproducir offline | `DefaultDrmSessionManager` modo playback + `keySetId` | APK |

Un `keySetId` es material sensible. En nuestra app se guardará cifrado mediante
Android Keystore, ligado a la descarga y borrado al eliminarla. Nunca se
incluirá en fixtures, logs o informes.

La transición de Media3 a estado completado debe persistir atómicamente item,
stream y referencia de licencia antes de retirar la operación activa. Un fallo
debe notificar y limpiar solo ese trabajo; la cancelación global de pendientes
observada en la app original no se conserva.

## Publicidad y previews

| Operación | Contrato | Evidencia |
|---|---|---|
| Tag de publicidad | `GET /api/videos/{idAsset}/publicidad.json` | APK |
| Preview simple | `GET https://videopreviews.rtve.es/tiivii-previews/api/preview?idasset={ids}&customer-id=rtve` | APK |
| Sprite de scrub | `GET https://videopreviews.rtve.es/tiivii-previews/api/sprite?idasset={id}` | APK |

El tag de anuncios puede contener placeholders de tamaño, cachebuster,
identidad de app, Play+, continuar viendo, dispositivo y consentimiento. Solo se
rellenarán valores consentidos y necesarios.

El sprite devuelve `spriteUrl` y `vttUrl`; el VTT se transforma en regiones de
la imagen. Las previews simples devuelven `assetId` y `previewUrl`.

## Recomendaciones, estadísticas e imágenes

Recomendaciones observadas:

| Función | Contrato |
|---|---|
| Relacionados de programa | `GET https://recomsys.rtve.es/recommendation/program?source=apps&item=/pr/{id}` |
| Relacionados de documental | `GET https://recomsys.rtve.es/recommendation/documental?source=apps&item=/v/{id}` |
| Relacionados de película | `GET https://recomsys.rtve.es/recommendation/film?source=apps&item=/v/{id}` |
| Página Mi RTVE | URL `recommendation/page` entregada por configuración |
| Tops y géneros | URLs `recommendation/tops` entregadas por configuración |
| Click de recomendación | `POST https://rtve.production.thefilter.com/v0/events` |

Las respuestas simples reutilizan el sobre paginado y añaden `recoData`; una
página personalizada contiene `recommendations[]`, cada una con título,
posición, página y `recoData`. El device ID puede añadirse como `did`; el UID
solo se añade con consentimiento. En el cliente nuevo ambos serán opcionales y
las recomendaciones tendrán fallback editorial no personalizado. El evento de
click es telemetría, no un requisito para reproducir ni navegar.

Metadatos auxiliares de analítica editorial:

```text
GET https://api2.rtve.es/api/videos/{id}/stats.json
GET https://api2.rtve.es/api/audios/{id}/stats.json
GET https://api2.rtve.es/api/programas/{id}/stats.json
```

El player original los usa para enriquecer analítica. No forman parte del
contrato de reproducción y un fallo debe ignorarse.

Imágenes derivadas observadas:

```text
https://img.rtve.es/p/{programId}?imgProgApi={semantic}
https://img.rtve.es/v/{videoId}[/{variant}]
https://img.rtve.es/a/{audioId}
https://img.rtve.es/n/{newsId}
```

`semantic` incluye `imgBackground`, `imgPoster`, `imgPortada`, `imgBanner` y
variantes `2`; para vídeo se vieron `vertical` y `horizontal2`. El servicio
acepta `w` y/o `h` como query. Debemos elegir el tamaño por densidad y constraints
reales, conservar aspect ratio y dejar que la caché de imágenes haga su trabajo.

## Rutas auxiliares y código latente

La configuración contiene plantillas auxiliares de VOD en
`television.secciones.auxiliares`, entre ellas un MPD y un HLS sobre un host de
mediavod. En el APK analizado el único getter de `Auxiliares` devuelve siempre
`null` y no se encontró ningún consumidor de esas plantillas; los flags
`subsaux` y `apiAux` también carecen de referencias funcionales.

Por tanto son **datos latentes, no un mecanismo activo verificado**. Se
conservarán como campos desconocidos de configuración, pero no serán fallback
de reproducción hasta validarlos mediante contrato público y autorización. La
misma regla se aplica a cualquier URL remota presente pero no alcanzable desde
el flujo de la versión analizada.

## Cuenta y personalización

Base observada:

```text
https://secure2.rtve.es/usuarios/services/
```

Todas estas operaciones requieren `x-jwt-rtve` y, cuando corresponde,
`profileId` y `App`:

| Función | Método y ruta | Cuerpo/consulta principal |
|---|---|---|
| Perfiles | `GET getProfiles` | `App` |
| Crear infantil | `POST addProfile` | nombre, avatar, target |
| Modificar perfil | `POST modifyProfile` | preferencias o edad |
| Existe PIN | `GET existsPin` | — |
| Comprobar PIN | `GET checkPin` | `parentalPin` |
| Definir PIN | `POST definePin` | `parentalPin` |
| Historial | `GET getHistoric` | page, tipology, secConsumed, profileId |
| Historial por programa | `GET getHistoric` | programRef + parámetros anteriores |
| IDs de historial | `GET getHistoricIds` | historic_array, profileId |
| Reanudar asset | `GET resumeHistoricId/{idAsset}/` | profileId |
| Guardar progreso | `POST setHistoric` | cmsId, progreso, duración, tipo, pago, sesión |
| Guardar lote | `POST setHistoricArray` | items locales |
| Borrar progreso | `GET deleteHistoric/{idAsset}/` | profileId |
| Favoritos | `GET myFavorites`, `POST addFavorites`, `POST delFavorites` | programa, medio, profileId |
| Listas | `GET myLists/{id}/contents`, `POST addToList`, `POST delFromList` | lista, item, tipo, profileId |
| Login backend | `GET notifyUserLogin` | — |
| Borrar cuenta | `POST deleteUser` | `App` |

No se documentan claves del cliente Gigya ni ejemplos de JWT. El adaptador se
considera opcional hasta validar una integración permitida.

## Caché y consistencia

Política recomendada por tipo:

| Datos | Fresh | Stale utilizable | Invalidación |
|---|---:|---:|---|
| Configuración remota | `expirationTime` (actualmente 1 h) | 7 días | versión/fecha o error de validación |
| Portada | `refresh` (actualmente 120 s) | 24 h | pull-to-refresh |
| Colección/programa | 15 min | 7 días | modificación/expiración |
| Búsqueda | 5 min | no necesaria | nueva consulta |
| Directos | 30 s | 2 min | cambio de ventana/live |
| Now/next | 30 s | 5 min | fin del slot |
| Parrilla | 15 min | 48 h | cambio de día |
| Detalle VOD/audio | 15 min | hasta `expirationDate` | respuesta 404/403 |
| URL resuelta/token | nunca persistente | no | expiración o fallo |
| Licencia offline | según licencia | no | borrado/expiración |

Esta tabla es decisión de nuestra implementación, no comportamiento observado
del APK.

## Seguridad de red

- TLS del sistema sin trust manager personalizado.
- `usesCleartextTraffic=false` y Network Security Config restrictiva.
- allowlist inicial: `rtve.es` y subdominios explícitamente necesarios; los
  hosts externos de stories/recomendaciones se habilitan por feature.
- máximo de redirecciones y validación del host en cada salto.
- timeouts separados para DNS/conexión/lectura y cancelación cooperativa.
- no registrar query params de suscripción, JWT, cookies, licencias ni tags de
  anuncios completos.
- sanitizar HTML y URLs de imágenes entregadas por feeds.
- rate limiting y backoff con jitter para no castigar servicios públicos.

## Tests de contrato mínimos

1. Decodificar portadas TV/radio y conservar filas desconocidas.
2. Aplanar una colección sin perder el item de colección.
3. Normalizar programa/program, vídeo, audio y directo.
4. Aceptar `type` objeto/string y IDs número/string.
5. Paginar con `query` relativo y rechazar cambio de host.
6. Parsear parrilla TV y radio en sus dos esquemas.
7. Validar deep links de programa, vídeo, directo y content URL.
8. Resolver las matrices VOD/live/start-over con un servidor falso.
9. Mapear 401/403/404/429/5xx, timeout, JSON parcial y array vacío.
10. Impedir que logs/snapshots contengan `x-jwt-rtve`, parámetros de
    suscripción, cookies o `keySetId`.
