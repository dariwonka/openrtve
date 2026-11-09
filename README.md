# Clan Peques

Cliente libre del catálogo infantil público de RTVE (Clan) para **Android TV**.
Es una versión recortada de [OpenRTVE](https://github.com/pablocpas/openrtve),
pensada para que la usen niños con el mando de la tele.

Clan Peques no está afiliado a RTVE ni respaldado por ella. Clan y RTVE Play
son marcas de la Corporación de Radio y Televisión Española.

## Qué cambia respecto a OpenRTVE

- **Inicio = todos los títulos.** La app abre directamente una cuadrícula de
  cuatro columnas con los títulos de Clan en orden alfabético, sin filas
  editoriales ("Princesas", "Para descubrir dinosaurios"…). Cada título sale
  una sola vez.
- **Series y Películas.** Dos botones en la barra superior cambian de lista
  (Series es también el inicio). A la derecha, Buscar y Ajustes. No hay menú lateral.
- **Selector.** La tarjeta enfocada lleva un borde rojo y su fila se mantiene en
  el centro de la pantalla, salvo la primera y la última.
- **Página de una serie:** solo su título y sus vídeos completos en cuadrícula,
  hasta el final (las páginas siguientes se cargan solas). Cada vídeo muestra
  título, temporada y episodio, fecha y duración, y se reproduce al pulsarlo.
  Una película se reproduce directamente desde el inicio.
- **Buscador infantil.** Busca con el perfil infantil de RTVE (`isChild=true`),
  el mismo que usa la app oficial de Clan para Android TV: encuentra también
  dibujos del archivo de RTVE que no están en la portada de Clan.
- **Recorrido de corrido.** En las cuadrículas, la derecha en la última columna
  pasa a la fila siguiente y la izquierda en la primera, a la anterior.
- **Audio en español** por defecto cuando el vídeo lo trae.
- **Solo infantil.** No hay Explorar, los "Similares" del recomendador general
  están cerrados y la app no abre enlaces externos de `rtve.es`
  (`ClanCatalogRepository`).
- **Solo Android TV**, con identidad propia (`es.clanpeques`): se instala junto
  a OpenRTVE.

El reproductor, la caché y el historial de lo visto son los de OpenRTVE.

## De dónde sale el catálogo

- **Series:** del listado A-Z de la cadena Clan
  (`api2.rtve.es/api/cadenas/417/programas.json?inazlist=true&play=true`, el
  patrón que la configuración de RTVE da para el buscador A-Z de cada cadena),
  más las series que aparezcan en las filas de la portada de Clan.
- **Películas:** los vídeos que RTVE clasifica como película, las piezas de un
  contenedor de cine y lo que venga en una fila titulada como películas o cine
  ("Todo el cine", "Cine para ver en familia").

`buildClanCatalog` lo funde todo en dos listas de títulos únicos. La última copia
se guarda (`ClanCatalogStore`) y es lo que se pinta al abrir la app; la versión
nueva se descarga después y la sustituye solo si ha cambiado.

## Instalar

Descarga el APK de la última ejecución de *Actions* (artefacto `clan-peques`) e
instálalo en la tele, por ejemplo con *Downloader* o `adb install`.

La CI compila la variante *release* (optimizada con R8), que es la que va
fluida en una tele; la de depuración es varias veces más lenta. Todos los APK
van firmados con `signing/sideload.jks`, una clave que está en el repositorio a
propósito: no es secreta y solo sirve para que cada APK se instale encima del
anterior. No vale para publicar en una tienda.

## Compilar

```bash
./gradlew assembleRelease
./gradlew testDebugUnitTest lintDebug
```

Sin SDK en el host, con Docker: `docker build --output type=local,dest=build/docker .`

## Documentación heredada

Los documentos de `docs/` son los de OpenRTVE y describen la API de RTVE y la
arquitectura original, incluida la interfaz de móvil que aquí no existe.

## Licencia

[GPL-3.0](LICENSE), como OpenRTVE, de cuyo código deriva.
