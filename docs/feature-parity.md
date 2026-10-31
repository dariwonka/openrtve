# Paridad funcional y criterios de aceptación

## Leyenda

Esta matriz separa conocer el mecanismo de haberlo validado en ejecución:

| Estado | Significado |
|---|---|
| `S` | Especificado mediante análisis estático |
| `H` | Contrato HTTP público muestreado |
| `D` | Pendiente de validar dinámicamente en dispositivo |
| `A` | Requiere cuenta o autorización legítima |

La prioridad no implica copiar la interfaz original. Define la cobertura que
debe ofrecer un cliente alternativo de primera clase.

## P0: primera versión útil

| Capacidad | Evidencia | Criterio de aceptación |
|---|---|---|
| Configuración remota | S/H | Arranca online y offline con última configuración válida; una sección inválida no bloquea el resto |
| Menú de categorías | S/H | Explorar enseña las portadas públicas (`tipo: portada`) de `menuBloques`, incluidas las de los `submenu` (temáticas, canales, emisoras), sin lista compilada |
| Portada TV dinámica | S/H | Interpreta colección, directos, catálogos, historias y módulos desconocidos sin crash |
| Portada y catálogo de radio | S/H | Radio es una sección de primer nivel aunque la app analizada la tenga desconectada |
| Colecciones | S/H | Abre colección, pagina y navega programa/VOD/audio según el tipo real |
| Programa | S/H | Muestra detalle, temporadas, completos, clips, audios, noticias y relacionados disponibles |
| VOD público | S/H/D | Reproduce HLS/DASH compatible con ABR, seek, reintento y reanudación |
| Audio/pódcast público | S/H/D | Reproduce MP3, cola, anterior/siguiente, velocidad, background y MediaSession |
| Directos TV | S/H/D | Lista agrupadores, reproduce broadcast/petición y recupera ventana en directo expirada |
| Radio en directo | S/H/D | Lista emisoras, reproduce y actualiza ahora/siguiente |
| Búsqueda | S/H | Busca desde tres caracteres, separa programas/multimedia y pagina cada bloque |
| A-Z | S/H | Filtra por medio/cadena/categoría usando fuentes remotas |
| Parrilla TV | S/H | Muestra canal/día, resalta actual y abre directo o VOD asociado |
| Parrilla radio | S/H | Soporta esquema semanal y ahora/siguiente |
| Derechos básicos | S/H/D | Distingue login, suscripción, región, emisión terminada y disponibilidad desconocida |
| Control parental local | S/D | Bloquea por edad antes de resolver el stream y no filtra PIN en logs |
| Deep links | S/D | Resuelve `/pr/`, `/v/`, `/d/` y URLs web permitidas desde proceso frío/caliente |
| Caché resiliente | diseño | Portada y catálogo funcionan con datos stale; se ve claramente cuándo falta red |
| Accesibilidad Android | diseño | TalkBack, foco, tamaños de texto y controles describibles pasan auditoría básica |

## P1: experiencia de reproducción completa

| Capacidad | Evidencia | Criterio de aceptación |
|---|---|---|
| Selección de audio | S/D | Enumera y cambia pista sin reiniciar la reproducción |
| Subtítulos | S/D | Auto, manual y desactivado; respeta preferencia de perfil/local |
| Calidad y ahorro de datos | S/D | ABR por defecto; selección/limitación persistente y `resMax=576` cuando corresponda |
| Velocidad | S/D | 0.5x, 0.75x, 1x, 1.25x y 1.5x en VOD y audio |
| PiP | S/D | Entra/sale sin perder posición y ofrece play/pausa |
| MediaSession | S/D | Lock screen, auriculares, Bluetooth, audio focus y notificación coherentes |
| Autoplay y cola | S/D | Siguiente episodio configurable, cola visible y sin límite artificial de tres items |
| Start-over | S/D/A | “Desde el inicio”, volver a directo y ventana DVR funcionan para combinaciones DRM/no DRM |
| Previews de seek | S/D | Sprite+VTT se carga bajo demanda y falla sin romper el seekbar |
| Cast vídeo | S/D/A | VOD/directo mantienen metadata, duración y derechos en dispositivo receptor |
| Cast audio | S/D/A | Directo/pódcast reproducen con cola y metadata |
| DRM online | S/D/A | Widevine autorizado funciona en dispositivos L1/L3 y mapea errores de licencia |
| Anuncios | S/D/A | IMA respeta consentimiento, no bloquea contenido si el tag es inválido y no fuga IDs |
| Territoriales | S/D | Aplica ventana y región con opción manual/fallback nacional |

## P1: cuenta y continuidad

| Capacidad | Evidencia | Criterio de aceptación |
|---|---|---|
| Login/logout | S/A/D | Sesión legítima, refresh, cierre y revocación sin afectar catálogo anónimo |
| Perfiles | S/A/D | Selección, modificación y perfil infantil aislados por `profileId` |
| PIN parental | S/A/D | Crear, comprobar y modificar edad con rate limiting local |
| Historial | S/A/D | Guarda por intervalo y eventos; sincroniza offline con resolución determinista |
| Seguir viendo/escuchando | S/A/D | Reanuda en otro dispositivo y trata ≥96 % como finalizado |
| Favoritos | S/A/D | Añadir/eliminar de forma optimista con rollback en error |
| Mi lista | S/A/D | Lista, añade, elimina y pagina contenidos |
| Play+ | S/A/D | Solo adjunta credenciales a hosts permitidos y jamás las persiste en logs/caché HTTP |

## P1: descargas

| Capacidad | Evidencia | Criterio de aceptación |
|---|---|---|
| Descarga de vídeo | S/D/A | Calidad/idiomas seleccionables, progreso, pausa, reintento y borrado |
| Widevine offline | S/D/A | Adquisición, reproducción offline, expiración y renovación autorizadas |
| Descarga de pódcast | S/D | Archivo validado, progreso, reanudación y borrado consistente |
| Cuota de almacenamiento | diseño | Límite configurable, estimación previa y error específico de espacio |
| Restricciones de red | diseño | Solo Wi-Fi opcional, trabajo persistente y respeto a batería/datos |

## P2: cobertura secundaria

| Capacidad | Evidencia | Criterio de aceptación |
|---|---|---|
| Noticias y destacado multimedia | S | Render seguro de texto/HTML y apertura del media asociado |
| Fotogalerías | S | Navegación, zoom y accesibilidad |
| Stories/vertical | S/H | Adaptador opcional; un proveedor externo caído no afecta la portada |
| Canales FAST | S/H | Parrilla y directo desde configuración, sin lista compilada |
| Infantil | S/H/D | Portada, búsqueda y filtros propios; sin recomendaciones adultas |
| Recomendaciones | S/H/D | Fallback editorial si el proveedor no responde; device ID opcional |
| Promociones/avisos | S | Motor remoto limitado y seguro, nunca bloquea el arranque indefinidamente |
| Notificaciones | S/D | Vídeo, directo y colección; canales y opt-in claros |
| Android TV | S/D | D-pad, focus, Leanback recommendations/media home y reproducción |
| Wear/controles remotos | S/D | Solo si aporta valor después de MediaSession estándar |

## Pruebas por flujo

### Arranque

1. Instalación limpia online.
2. Segundo arranque con configuración fresca.
3. Arranque sin red con caché válida y con caché caducada.
4. JSON remoto parcial, desconocido y malformado.
5. Deep link y notificación antes/después de sincronizar.
6. Cuenta caducada sin bloquear el modo anónimo.

### Catálogo

1. Portada con 0, 1 y muchas filas.
2. Un módulo devuelve 404/500/timeout y los demás siguen visibles.
3. Colección con programas, multimedia y item desconocido mezclados.
4. Programa sin temporadas, con temporadas vacías y con muchas páginas.
5. IDs numéricos/string y `type` objeto/string.
6. Cambio de orden editorial sin reinstalar la app.

### Reproducción VOD

1. No DRM por ruta pública directa y por resolver heredado autorizado.
2. DRM con licencia concedida, denegada, expirada y timeout.
3. 403 regional, 404 manifest, 429 y 5xx.
4. Cambio HLS/DASH cuando exista fallback.
5. Seek, rotación, background, PiP y process recreation.
6. Audio/subtítulo múltiple, desconocido y sin pistas.
7. Progreso 0 %, intermedio, 96 %, 99 % y final.
8. Datos móviles desactivados y modo ahorro.

### Directo

1. `broadcast` continuo, `peticion` activa y petición terminada.
2. MPD DRM, MPD libre, HLS y fallback TV.
3. `ERROR_CODE_BEHIND_LIVE_WINDOW`.
4. Start-over con/sin `sgce`, con/sin DRM y base Play+.
5. Cambio a territorial y vuelta a nacional.
6. Programación `before/now/next` ausente o desactualizada.

### Audio

1. MP3 directo y redirección consumer.
2. Background durante una hora y recuperación tras pérdida de audio focus.
3. Cola ordenada, autoplay desactivado y final de cola.
4. Descargado local con y sin red.
5. Sincronización de progreso local/remoto.

### Descargas

1. Reinicio de proceso durante descarga.
2. Red intermitente, cambio Wi-Fi/móvil y falta de espacio.
3. Pistas de idioma/calidad y estimación de tamaño.
4. Licencia válida, próxima a caducar, caducada y no renovable.
5. Borrado atómico de media, metadata y `keySetId`.
6. Logout y cambio de perfil/cuenta.

## Requisitos no funcionales de primera clase

| Área | Objetivo inicial |
|---|---|
| Arranque caliente | portada cacheada visible en <500 ms en dispositivo medio |
| Arranque frío | shell útil en <1 s; red nunca bloquea la primera pintura |
| Reproducción | player preparado en <2 s con red adecuada, medido p50 |
| Estabilidad | ≥99.8 % de sesiones sin crash |
| Red | cancelación estructurada, backoff y máximo de concurrencia por host |
| Offline | catálogo stale y descargas accesibles sin DNS |
| Memoria | sin `largeHeap`; imágenes dimensionadas y listas perezosas |
| Batería | audio por MediaSession/foreground service; trabajos con constraints |
| Privacidad | analítica y anuncios opt-in/consentidos; cero tokens en logs |
| Seguridad | TLS del sistema, cleartext desactivado, componentes no exportados por defecto |
| Accesibilidad | navegación completa con TalkBack, switch access y D-pad |
| Observabilidad | métricas propias por fase sin incluir URLs/token sensibles |

Los umbrales de latencia se ajustarán después de una línea base reproducible;
son objetivos del nuevo producto, no métricas extraídas de RTVE Play.

## Orden recomendado de implementación

1. `core-network`, configuración y fixtures de contrato saneados.
2. modelos canónicos y adaptadores de portada/colección/programa.
3. navegación Compose y catálogo TV/radio.
4. Media3 público: VOD, audio y directo.
5. parrillas, búsqueda, deep links y errores tipados.
6. MediaSession, PiP, pistas, velocidad, cola y previews.
7. cuenta/historial si existe una vía de autenticación admitida.
8. DRM y descargas autorizadas.
9. Cast, territoriales, anuncios y superficies secundarias.
10. Android TV y endurecimiento final.

## Configuración por formato

La app móvil (`rtve.tablet.android`) lee `estructura2.json`; la de Android TV
(`com.rtve.androidtv` 8.1.9) lee `estructuraAndroidTV2.json`, solo con el árbol
`television`: menú distinto (añade `portada4k`, Cuéntame y Arxiu Catalunya;
no trae canales temáticos, territoriales ni Participa), parrilla con Catalunya
y Canarias y otro conjunto de `canalestematicos`. OpenRTVE usa hoy
`estructura2.json` en ambos formatos.

El árbol `radio` solo existe en `estructura2.json` y RTVE Play no lo pinta en
ninguna versión: el catálogo de audio vive en la app RTVE Audio
(`es.rtve.playradio`), y el menú de Play solo ofrece "Ir a RNE Audio". Desde
septiembre de 2026 Explorar tampoco lo muestra. Decisión del 31-10-2026: OpenRTVE es solo vídeo y no
incluye radio ni pódcasts (portada, emisoras, fichas de audio, MP3 ni audio en
segundo plano); si se aborda, será una app aparte que comparta el núcleo.

Menú de RTVE Play 8.8.1 verificado en `MenuFragment`: pestaña "Menú" de la
barra inferior, lista plana en el orden del JSON y sin cabeceras; cada
`submenu` se despliega en acordeón. OpenRTVE lo pinta como rejilla: las
portadas del bloque principal sin cabecera y cada submenú como sección.

Tipos de menú que reconoce la app de TV (`MenuUtils.checkMenuType`) y estado
en OpenRTVE:

| `tipo` | Estado |
|---|---|
| `portada`, `submenu` | Cubierto: Explorar |
| `directos` | Parcial: solo las filas de directos de las portadas; sin pantalla por agrupadores |
| `buscadorAZ` (`secciones.buscadorAZ.canales/categorias`) | Pendiente |
| `parrilla` (`secciones.parrilla`) | Pendiente; las filas `Parrilla` de portada se descartan |
| `portada4k` | Pendiente; requiere comprobar pantalla y códec |
| `portadaCanalTematico` | Cubierto: sección "Canales temáticos" en Explorar; abre la lista con el directo (la app oficial muestra además su parrilla) |
| `territoriales` (solo móvil) | Pendiente |
| `seguirviendo` | Cubierto en local, sin cuenta |
| `mislistas`, `iniciarSesion`, `cerrarsesion`, `control_parental` | Requieren cuenta (`A`) |
| `configuracion`, `informacion`, `inAppHtml`, `intentApp` | Ajustes propios; el resto no aplica |

## Filas de portada de RTVE Play 8.8.1

Verificado en `PortadaAdapter` y sus layouts. El criterio es que todo lo que
la app oficial muestra aparezca y que dos `tipo` con presentación distinta en
RTVE Play también se distingan aquí, reinterpretados en Material 3 (los
tamaños no se copian). Cuando la app oficial comparte holder (`directosTV` y
`directosTV16`; `ColeccionCuadrado` y `ColeccionCuadradoPeq`, que solo
cambian de tamaño), aquí también se comparte.

| `tipo` | RTVE Play | OpenRTVE (`RowLayout`) |
|---|---|---|
| `ColeccionDestacado` | carrusel de 500dp con título, subtítulo y "Ver" | `HERO`: carrusel a ancho completo |
| `ColeccionSuperDestacado` | un destacado: imagen, título, descripción, botón "Ver ahora" | `FEATURED`: tarjeta con imagen, título, descripción y botón |
| `ColeccionPoster`, `videoPoster`, `programas` | pósters 2:3 (`imgPoster`) | `POSTER` |
| `ColeccionSuper` | pósters altos 1:2 con el recorte `imgCol` | `POSTER_TALL`, con `imgCol` |
| `ColeccionApaisado`, `videos`, `directosTV`, `directosTV16` | 16:9 | `LANDSCAPE` |
| `ColeccionCuadrado`, `ColeccionCuadradoPeq` | cuadrada (215 / 137 dp) | `SQUARE` |
| `ColleccionTops`, `Tops` | apaisada con número | `RANKED`: apaisada con número |
| `links` | tiles con imagen y título; van inline en la fila (`links[]`) | tiles 16:9; `enlaceExterno` (web) se omite |
| `StoriesPoster` | feed vertical de un CDN de terceros | excluida por diseño: host fuera de `rtve.es` |
| `Parrilla` | guía embebida | futuro, junto con la Guía TV |
| `seguirviendo` | historial de cuenta | local |
| `recomendaciones`, `shorts` | recomendaciones por id de dispositivo; visor de vídeos verticales | descartados (decisión de septiembre de 2026) |
| `favoritos`, `MiRtve`, `benidorm26`, `moduloNowNextCanal`, `LogoRadio`, `audios`, `moduloDirectoRadio` | cuenta, campañas o radio | no aplica |
| `noLabels` | oculta el título bajo la tarjeta | ignorado (presentación) |

Con esto, la portada cubre todos los `tipo` públicos de RTVE Play salvo
`Parrilla`, con presentación propia en móvil y en Android TV.

## Condición para declarar paridad

La ingeniería estáticamente reconstruida ya cubre los mecanismos de la versión
8.8.1, pero la etiqueta “paridad completa” solo se aplicará cuando todas las
filas P0 y P1 tengan pruebas automatizadas o evidencia dinámica y no quede
ninguna marcada exclusivamente `D`/`A`. Los fallos inevitables por cambio de API
deben degradar de forma localizada y observable.
