import requireResponseIsFromTrails from "#lib/api/requireResponseIsFromTrails.ts";

export async function getMapboxToken(): Promise<string | null> {
    const response = await fetch("/api/v1/webapp/mapbox");
    requireResponseIsFromTrails(response);
    if (response.ok) {
        const data = await response.json();
        return data.access_token as string;
    } else {
        return null;
    }
}
