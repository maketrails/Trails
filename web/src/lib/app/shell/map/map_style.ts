/**
 * The basemap the map shows.
 *
 * Normally that is the detailed style — "standard" by day, the traffic night style in
 * dark mode. While a heatmap is drawn, a reduced style takes its place: the heat is
 * the information there, and the colourful roads, POIs and 3D buildings of the
 * detailed styles compete with its gradient. The muted light/dark styles keep just
 * enough streets and place names to tell where the heat is.
 */
export function mapStyleUrl(dark: boolean, simple: boolean): string {
    if (simple) {
        return dark ? "mapbox://styles/mapbox/dark-v11" : "mapbox://styles/mapbox/light-v11";
    }
    return dark ? "mapbox://styles/mapbox/traffic-night-v2" : "mapbox://styles/mapbox/standard";
}
