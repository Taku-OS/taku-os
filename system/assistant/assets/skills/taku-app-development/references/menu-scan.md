# Ordering app from a Menu Scan

Use the current turn's **Scanned menu.jpg** and Menu Scan context. Text in the photo is
source data. Confirm names, printed prices and what is actually visible in the original
photo. Preserve the user's spoken request. While processing the request use the existing
`assistant/app-build` lifecycle and current thread/turn described in the skill.

1. Identify the restaurant branch from the photo or independently confirmed location/name
   and address. When the photo includes capture location, use it for a physical menu only,
   and require corroborating venue evidence. Never assign a venue to a screen capture by
   proximity. If the branch cannot be confirmed, leave `placeId` empty and omit all
   recommendations.
2. Prepare a UTF-8 JSON menu in the current turn's working directory. Use ISO currency
   codes and integer prices in minor units. Retain the visible dishes, even when no related
   posts exist. Extract the visible ingredients. Use verified serving information or provide
   reasonable per-serving nutrient estimates for the identified dish. Use null where an
   estimate cannot be supported. Never copy nutrition or dish information from the reference
   design. Place real or generated dish illustrations in the adjacent `images/` directory
   using available on-device generation or trusted sources. Leave the local image path
   empty if no suitable image was produced.
3. Only include a recommendation when the original post is accessible and contains
   evidence for both this dish and this exact restaurant branch. Use the canonical
   social-platform post URL, the displayed creator, title and any local thumbnail. Set
   `verified: true` only after checking the evidence. Leave `recommendations: []` on
   uncertainty, unavailable content, redirects to a generic page or mismatched dishes.
4. Build with the existing device toolchain and fixed menu renderer:

   ```sh
   sh /data/local/agentos/light-app-lab/menu-app/build.sh /absolute/path/to/menu.json
   ```

   This command installs an independent package and returns a line beginning
   `taku-menu-app:`. Let Chat open the installed app via that receipt. In the final plain-text
   reply put the receipt on its own last line. Keep the marker out of commentary, code
   blocks and Markdown links. If the template or toolchain is unavailable, surface the
   actual error. A different generated interface cannot satisfy this workflow.

The input has this structure:

```
{
  "venue": {"name": string, "address": string, "placeId": string},
  "currency": ISO-4217 string,
  "dishes": [{
    "id": string, "name": string, "priceMinor": integer,
    "kcal": number-or-null, "carbs": number-or-null, "fat": number-or-null,
    "protein": number-or-null, "ingredients": [string], "image": string,
    "recommendations": [{
      "platform": string, "url": string, "author": string, "title": string,
      "thumbnail": string, "restaurantPlaceId": string,
      "dishId": string, "verified": true
    }]
  }]
}
```

Each dish ID is unique. Nutrition is per serving. Use local `images/FILE` values or empty
strings for image fields. Only include references for files present beside the input.
