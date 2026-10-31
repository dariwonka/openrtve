# Arquitectura inicial

## Principios

OpenRTVE empieza como una aplicación de un solo módulo. La separación es por
paquetes y por dirección de dependencias, no por una colección de módulos
Gradle vacíos:

```text
UI móvil ─┐  NavigationViewModel (una pila por pestaña + destino global)
          ├──> PortadaViewModel ──┐
UI TV ────┘    ModuleViewModel   ├─> CatalogRepository ─> HTTPS + caché
               ProgramViewModel  │
               SearchViewModel   │
               ExploreViewModel ─┘

UI ─> PlaybackResolver ─> PlayerActivity ─> MediaController
                                      └───> MediaSessionService + ExoPlayer
```

- Estado inmutable mediante `StateFlow`; un ViewModel por pantalla. El
  pegamento de cada pantalla (crear el ViewModel, observar su estado, cruzarlo
  con el historial, entregar los errores una vez) está en `ui/ScreenStates.kt`
  y lo comparten móvil y TV; las pantallas solo pintan. Las acciones comunes
  (abrir fichas, reproducir o explicar por qué no, mensajes) están en
  `CatalogActions`.
- Navegación propia y pequeña, siguiendo los principios de Navigation de
  Android: Inicio es el destino inicial fijo y cada una de las tres pestañas
  conserva su pila. Ajustes es un destino global y nunca se guarda dentro de
  una pestaña; elegir una pestaña lo cierra y recupera la posición anterior.
  Los enlaces externos construyen una pila sintética `Inicio -> ficha`. Cada
  entrada (clave `pestaña/posición` o `global/ajustes`) tiene su
  propio `ViewModelStore`, que se libera al salir de ella, y `DestinationHost`
  la envuelve en un `SaveableStateHolder`: al volver atrás se recuperan scroll y
  foco, y los ViewModels de las fichas no se acumulan durante la sesión. Las
  rutas mínimas (pestaña, pilas y destino global) también se escriben en un
  `SavedStateHandle`, por lo que Android puede reconstruirlas tras matar el
  proceso; los datos completos se vuelven a cargar desde el repositorio.
- La presentación de cada fila (`RowLayout`) se deduce del `tipo` editorial
  en el parser; la UI solo decide tamaños.
- La portada carga cada fila por separado (máximo seis en paralelo) para
  que una sección lenta o rota no bloquee las demás; las vacías se ocultan.
  El patrón de secciones + carrusel + tarjeta con imagen procede de Findroid.
- Toda captura de excepciones en corrutinas relanza `CancellationException`:
  un `runCatching` alrededor de una suspensión convertiría una cancelación en
  un error visible. Las llamadas bloqueantes de OkHttp van bajo
  `runInterruptible(Dispatchers.IO)` y `SafeHttpsClient` cancela la llamada si
  interrumpen el hilo: un refresh o salir de la pantalla corta la descarga en
  vez de dejarla ocupando un permiso del semáforo hasta el timeout.
- Un repositorio como única entrada al catálogo.
- JSON dinámico convertido inmediatamente a modelos pequeños del dominio.
- Inyección manual en `Application`; no hay un grafo que justifique Hilt.
- Corrutinas para trabajo bloqueante y acceso de UI consciente del ciclo de
  vida.
- La caché es una mejora de disponibilidad, nunca la fuente de URLs temporales,
  tokens o licencias.

## Móvil y TV

Datos, estado y política son comunes. Las superficies no lo son:

- móvil usa Material 3, ventana edge-to-edge y `NavigationSuiteScaffold`: la
  navegación cambia entre barra inferior y rail según el tamaño y la postura
  de ventana que calcula Material, incluidos multiventana y plegables;
- TV sigue las guías de Compose for TV (patrón de la muestra JetStream):
  `NavigationDrawer` en el borde izquierdo, `Carousel` destacado en la portada,
  filas con `focusRestorer`, tarjetas que escalan al enfocar, márgenes de
  overscan de 48/27 dp y nada que dependa del táctil; atrás lo maneja el
  mando;
- el player se aloja en un único `MediaSessionService`, controlado por una
  actividad efímera en ambos dispositivos. La actividad solo carga un item si
  su `mediaId` difiere del que ya tiene la sesión: rotar, entrar en PiP o
  volver desde la notificación reanuda en vez de reiniciar. PiP y pantalla
  inmersiva se activan solo para vídeo.

La actividad móvil y el reproductor no bloquean la orientación: responden al
espacio disponible y respetan la rotación elegida por el usuario. Solo el
launcher exclusivo de Android TV declara `landscape`, como exige la checklist
de publicación de TV.

## Arranque

La portada raíz se pinta primero desde la copia local (`cachedOnly`), sin
esperar a la red, y después se revalida: las filas que ya estaban conservan su
contenido y solo cambian si la respuesta difiere. Sin red, esa copia se marca
como antigua en vez de mostrar un error. El Baseline Profile del módulo
`baselineprofile` recorre portada, Explorar, una categoría, Buscar, una ficha y
el reproductor; el plugin lo incrusta en el release como `assets/dexopt/baseline.prof`.

## Directos, "Seguir viendo" y refresco

El feed de directos trae programa en emisión (`titulo`), categoría
(`antetitulo`), `live`, `inicio`, `duracion`, `porcentaje` y `logo` del canal;
el parser lo convierte en `LiveInfo` y la UI calcula el progreso con el reloj
(un `LocalNowMillis` compartido que avanza cada 30 s), así la barra se mueve sin
red. Un directo programado (`live=false` con `inicio` futuro) se muestra con su
horario y el resolver lo bloquea hasta la hora.

"Seguir viendo" es local (`WatchHistory`, un JSON en `filesDir` escrito con
`AtomicFile` fuera del hilo principal): el reproductor guarda la posición cada
10 s y al salir; por debajo de 30 s no
cuenta y al 95 % el episodio pasa a "visto" (sale de la fila, pero la ficha del
programa lo usa para proponer el siguiente: `suggestPlay`, con Reproducir /
Continuar / Siguiente). La portada raíz pinta la fila tras el hero.

Refresco como la app oficial (`refresh: 120` en su configuración): la portada se
recarga al volver a primer plano si tiene más de dos minutos y, mientras está
visible, las filas de directos se recargan cada minuto (con ETag, un 304 si no
hay cambios).

## Enlaces

`parseDeepLink` es una función pura sobre la URL (formatos del manifiesto de
RTVE Play y URLs web; `content?uri=` se decodifica una sola vez) y
`DeepLinkResolver` la convierte en un item con la red necesaria: `videos/{id}`,
`audios/{id}`, `programas/{id}`, `lives/{idAsset}`; un permalink de programa se
casa contra el `htmlUrl` de los resultados del buscador y uno de directo contra
el feed de "Ahora en emisión". `MainActivity` (`singleTask`) recibe `VIEW` y
`SEND`; el segundo es el que no depende de la verificación de dominio. Los
enlaces llegan a la UI por un `LinkInbox` (canal conflated, se consumen una
vez); en TV, `MainActivity` reenvía el intent a `TvActivity` y esta lo trata igual.

## Reproductor

`PlayerView` de Media3 con un layout de controles propio
(`res/layout/player_controls.xml`) que replica la estructura del reproductor
de Findroid: acciones arriba, transporte en el centro, tiempos y barra abajo.
Los botones con id `exo_audio_track`, `exo_subtitle` y `exo_playback_speed`
los enlaza `PlayerControlView`, que abre sus propios selectores; los nuestros
(`player_back`, `player_pip`, `player_lock`, `player_aspect`) los maneja la
actividad. `PlayerGestures` añade toque, doble toque y deslizamientos. Findroid
es GPLv3: se toma el diseño, no el código.

Como en RTVE Play, un solo engranaje abre el panel de ajustes (`PlayerSettingsPanel`):
calidad (alturas reales de las pistas del stream; series y cine traen
1080/720/576/360), velocidad, audio (`qaa` = versión original, `ads` =
audiodescripción) y subtítulos, todo aplicado con `TrackSelectionParameters`.
Las miniaturas al arrastrar salen del sprite + VTT de `videopreviews.rtve.es`,
y el siguiente episodio de `videos/{id}/next.json`, con aviso en los últimos
20 s y encadenado automático si el ajuste está activo.

Ciclo de vida, según la guía de Android para reproductores de vídeo: al salir
de la pantalla (`onStop`) el vídeo se pausa salvo en PiP, y al cerrarla (atrás
o cerrar la ventana PiP) se detiene y vacía la sesión. En el móvil el
reproductor se abre apaisado (en tablets y plegables Android ignora la
orientación pedida). La pantalla solo se mantiene encendida mientras se
reproduce.

## Red y datos

Los streams y las licencias Widevine usan el mismo OkHttp (pool HTTP/2 y
User-Agent) sin la allowlist: `ztnr` redirige a un CDN y la licencia la sirve
Axinom, fuera de rtve.es; el texto plano lo prohíbe `network_security_config`.
Las imágenes de `img.rtve.es/p|v` se piden al tamaño en que se pintan
(`RtveImageSizing`), y la caché de documentos se poda por antigüedad al pasar
de 32 MB.

`RtveHostPolicy` (dominio) es la única allowlist: solo HTTPS, puerto estándar,
sin credenciales embebidas y host `rtve.es` o subdominio real. La aplican el
cliente HTTPS en cada salto de redirección, el parser sobre `urlContent` y las
URLs de imagen (elevando `http://` a HTTPS), y el resolver sobre `filePath`.
`SafeHttpsClient` corre sobre un único `OkHttpClient` compartido con Coil (una
conexión HTTP/2 multiplexada para catálogo e imágenes, gzip, pool), aplica la
allowlist en un interceptor de red (cada salto, redirecciones incluidas),
limita el tamaño de respuesta y usa los certificados del sistema. El pipeline de Media3 sigue los 302 de `ztnr` por su
cuenta; la Network Security Config impide cualquier salto en claro.

Los manifests de portada se cachean dos minutos y los módulos cinco. Con copia
local, la petición lleva `If-None-Match`: RTVE responde 304 si no ha cambiado y
la copia vuelve a contar como fresca sin descargar nada. Si falla la red se
admite una copia de hasta 24 horas y la UI la marca como stale. El
parser conserva strings desconocidos, acepta IDs numéricos o textuales y
aplana el nivel adicional de las colecciones.

## Cuándo extraer módulos

Solo extraeremos un módulo Gradle cuando exista al menos una de estas razones:

1. frontera de seguridad o API que deba ser imposible atravesar;
2. tiempo de compilación que mejore de forma medible;
3. reutilización real por otro artefacto;
4. equipo o ciclo de entrega independiente.

Los candidatos naturales, cuando se implementen, son reproducción/descargas,
cuenta y un posible cliente compartido. No se crean por anticipado.
