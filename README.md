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
- **Series y Películas.** Dos botones en la barra superior cambian de lista. A
  su derecha, Inicio y Ajustes. No hay menú lateral.
- **Página de una serie:** solo su título y sus vídeos completos en cuadrícula,
  hasta el final (las páginas siguientes se cargan solas). Cada vídeo muestra
  título, temporada y episodio, fecha y duración, y se reproduce al pulsarlo.
  Una película se reproduce directamente desde el inicio.
- **Solo Clan.** No hay Buscar ni Explorar, los "Similares" del recomendador
  general están cerrados y la app no abre enlaces externos de `rtve.es`
  (`ClanCatalogRepository`).
- **Solo Android TV**, con identidad propia (`es.clanpeques`): se instala junto
  a OpenRTVE.

El reproductor, la caché y el historial de lo visto son los de OpenRTVE.

## De dónde sale el catálogo

De la portada de Clan (`/play/clan/index_apps.json`): se descargan todas sus
filas y `buildClanCatalog` las funde en dos listas de títulos únicos. Un
capítulo aporta su serie; son películas los vídeos que RTVE clasifica así, las
piezas de un contenedor de cine y lo que venga en una fila titulada como
películas o cine. Un título que Clan no enseñe en ninguna fila de su portada no
aparece: falta enlazar el listado A-Z oficial, cuya URL está en la
configuración remota de RTVE.

## Instalar

Descarga el APK de la última ejecución de *Actions* (artefacto
`clan-peques-debug`) e instálalo en la tele, por ejemplo con *Downloader* o
`adb install`.

## Compilar

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest lintDebug
```

Sin SDK en el host, con Docker: `docker build --output type=local,dest=build/docker .`

El Baseline Profile de OpenRTVE recorría la interfaz de móvil y se ha retirado
junto con su módulo.

## Documentación heredada

Los documentos de `docs/` son los de OpenRTVE y describen la API de RTVE y la
arquitectura original, incluida la interfaz de móvil que aquí no existe.

## Licencia

[GPL-3.0](LICENSE), como OpenRTVE, de cuyo código deriva.
