# Arquitectura

Un solo módulo Android (Kotlin, Jetpack Compose para TV, Media3). Estado inmutable
con `StateFlow`, un ViewModel por pantalla y una inyección manual (`AppContainer`).

```text
TvActivity ─> TvApp ─┬─ TvCatalogScreen   (Series / Películas)  ─ ClanCatalogViewModel
                     ├─ TvSearchScreen    (buscador)            ─ SearchViewModel
                     ├─ TvProgramScreen   (página de una serie) ─ ProgramViewModel
                     └─ TvSettingsScreen  (ajustes, copia, eliminados)

TvApp ─> CatalogActions ─> PlayerActivity ─> MediaController ─> PlaybackService (ExoPlayer)
```

## Datos

- `CatalogRepository` (`DefaultCatalogRepository`) habla con la API de RTVE por
  HTTPS (`SafeHttpsClient`, con lista de hosts permitidos) y guarda las respuestas
  en `RawDocumentCache`. Siempre usa el perfil infantil en las búsquedas.
- `ClanCatalogViewModel` construye las dos listas (series y películas) fundiendo
  varias fuentes (`buildClanCatalog`) y las **acumula**: nada que se haya visto
  alguna vez desaparece. Lo guardado vive en `ClanCatalogStore`
  (`clan-catalog.json`): listas, anclados, desanclados a mano, eliminados y la
  primera vez que se vio cada título.
- `WatchHistory` (`watch-history.json`) guarda la posición y los vistos.
- `CatalogBackup` empaqueta ambos ficheros en un solo JSON para cambiar de tele.

## Reglas del catálogo

- Todo vídeo de 40 minutos o más es una película; un vídeo de menos de 4 no se
  enseña.
- **Anclados:** los pone el usuario o RTVE (sus novedades); solo el usuario los quita.
  Van arriba, por orden alfabético, salvo las novedades de RTVE recién llegadas,
  que van las primeras. Lo desanclado a mano no lo vuelve a anclar RTVE.
- **Eliminar** pide el PIN (una vez por sesión) y se puede deshacer desde Ajustes.
  Un título eliminado solo reaparece si RTVE lo retira y más tarde lo repone.
- Al abrir con copia guardada se pinta al instante, y las tres primeras filas
  (`CATALOG_HEAD_SIZE`) no cambian durante esa sesión; las novedades entran en el
  siguiente arranque o al anclar/añadir algo.

## Reproductor

`PlayerActivity` + `PlayerView` con `res/layout/player_controls.xml`. Las teclas
del mando se tratan en `dispatchKeyEvent` (saltos de 1 min con aceleración, centro
= pausa). Al arrancar fuerza la mejor calidad disponible; el selector del panel
manda después. El siguiente episodio y la posición de reanudación son automáticos.
