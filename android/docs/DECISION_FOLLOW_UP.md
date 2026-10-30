# After choosing in Decide: menu, directions, recipes and the plan on Home

Follow-up to BQ5/BQ8 (Daniel). What happens once the user chooses Cook, Walk or Order, and how the choice and context survive closing the app.

## Opening the choice

| Choice | Opens | Data |
|---|---|---|
| Walk | Restaurant detail: address, menu ("Available meals") and **Directions (walking)** | `GET restaurants/:id` (existing). Directions hand the restaurant's public name and address to the maps app (`https://www.google.com/maps/dir/?api=1&…&travelmode=walking`). The user's position is never sent by CampusMeal. |
| Order | Restaurant detail with the menu | Same endpoint |
| Cook | Recipes (BQ3) for the current inventory, diet and time; each recipe opens with its ingredients and steps | Local recipe catalog (S3-05) |

- A first choice opens it right away. The chosen card keeps a button ("Menu and directions", "See menu" or "See recipes") to open it again.

## Plan on Home

- `MealPlanStore` (DataStore `decision.plan.{accountId}`) keeps the chosen alternative:
  - recommendation id, alternative and time;
  - available minutes;
  - the restaurant id and name, or the Cook alternative's expiring ingredients (`itemId`, name).
- No coordinates. Deleted on logout or account change. Replaced by the next choice.
- **During lunch time** (choice time + available minutes): Home shows "Your plan", e.g. "Walk to … · Until 12:45".
  - Walk/Order offer the menu, plus directions for Walk.
  - Cook offers "See recipes" and "I cooked it".
  - Any plan can be dismissed.
- **After lunch time:**
  - A Walk/Order plan is removed.
  - A Cook plan asks "Did you cook it?". **Yes** marks those ingredients as consumed through the existing inventory consume flow (`InventoryRepository.consumeItem`, offline-safe like the Inventory screen). **No** removes the plan without touching the inventory.
- Home re-checks the time when it becomes visible and every 30 seconds while open.

## Context form

- **Available lunch time:** the preset chips (15/30/45/60) plus a field to type any other number of minutes (up to 3 digits; 0 or blank fails validation as before).
- **Maximum budget:** displayed with thousands separators (`25,000`). The value stays plain digits, so validation and the request are unchanged.
- **Dietary preferences:** vegan implies vegetarian. Choosing Vegan also selects Vegetarian, and removing Vegetarian also removes Vegan.
- **Allergies:** not implemented. The backend has no allergen data per meal (only `VEGETARIAN`, `VEGAN`, `GLUTEN_FREE`), so a filter would need a migration, catalog data and backend filtering.

## Manual verification

1. Choose Walk: the restaurant detail opens with the menu. "Directions (walking)" opens the maps app on foot.
2. Choose Cook: the recipes open; a recipe shows its steps.
3. Back on Home: "Your plan" shows the choice and its end time. Close the app completely and reopen: it is still there.
4. With a short time (e.g. type 1 minute), choose Cook and wait: Home asks "Did you cook it?". "Yes" marks the ingredients as consumed in Inventory.
5. Context form: typing 90 minutes works; the budget shows `25,000`; Vegan selects Vegetarian.
