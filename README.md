# Clan Peques

Cliente libre del catálogo infantil público de RTVE (Clan) para **Android TV**.
Es una versión recortada de [OpenRTVE](https://github.com/pablocpas/openrtve),
pensada para que la usen niños con el mando de la tele.

Clan Peques no está afiliado a RTVE ni respaldado por ella. Clan y RTVE Play
son marcas de la Corporación de Radio y Televisión Española.

## Qué cambia respecto a OpenRTVE

- **Solo Clan.** El inicio es la portada de Clan. No hay Buscar ni Explorar, la
  sección "Similares" (que sale del recomendador general de RTVE) está oculta y
  la app no abre enlaces externos de `rtve.es`. Todo el catálogo pasa por
  `ClanCatalogRepository`, que es quien cierra esas puertas.
- **Filtro por edad.** En el primer arranque se elige una franja; las franjas
  son las portadas del bloque infantil de la configuración remota de RTVE, sin
  lista compilada. "Todas las edades" es la portada completa de Clan. Se cambia
  en *Ajustes → Cambiar edad*, sin PIN.
- **Solo Android TV.** No hay interfaz de móvil. También aparece en lanzadores
  sin Leanback.
- Se instala junto a OpenRTVE (`es.clanpeques`), con su propio nombre e icono.

El reproductor, la caché, "Seguir viendo" y el resto del comportamiento son los
de OpenRTVE.

## Pendiente de comprobar

- Qué franjas de edad publica hoy RTVE y si cada una tiene portada propia.
- Si el buscador de RTVE filtra por perfil infantil en el servidor
  (`context=clan`, `isChild=true`). Hasta comprobarlo, la búsqueda está cerrada.

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
