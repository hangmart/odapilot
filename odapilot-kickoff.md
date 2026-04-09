# Odapilot — Claude Code Kickoff Prompt

## What is this?

Odapilot is a long-running service that acts as the single gateway between an AI agent (OpenClaw) and Oda (Norwegian online grocery store). It analyzes grocery order history, manages meal planning data, and proxies all Oda interactions — search, cart, orders, everything. The agent never talks to Oda directly; it all goes through Odapilot's local HTTP API.

## Architecture decisions already made

- Kotlin — write idiomatic Kotlin throughout. Prefer data classes, extension functions, sealed classes, coroutines. No Java-style boilerplate.
- Ktor for both HTTP server and client
- SQLite via sqlite-jdbc with raw SQL — no ORM unless it genuinely simplifies things
- Credentials passed as CLI args at startup, kept in memory only
- Runs as a daemon, syncs orders in the background
- Plain HTTP API, no MCP
- Always prefer the simplest solution and the smallest dependency. If something can be done with stdlib or a 10-line function, don't add a library.

## Reference material

The project `oda-mcp` (available locally and at https://github.com/hangmart/oda-mcp) is a TypeScript MCP server for Oda. Do NOT reuse code from it, but use it as inspiration for understanding the Oda API — which endpoints exist, what the requests and responses look like, and how auth works.

The upstream Python client it was based on is at: https://raw.githubusercontent.com/ludoergosum/ha-oda-component/refs/heads/master/custom_components/oda/oda.py — this is useful for understanding the full auth flow, CSRF handling, and response parsing.

## Phase 1: Project scaffold

Set up a Kotlin project with Gradle (Kotlin DSL). Keep dependencies minimal:

- **io.ktor:ktor-server-netty** — HTTP server
- **io.ktor:ktor-server-content-negotiation + ktor-serialization-kotlinx-json** — JSON
- **io.ktor:ktor-client-cio + ktor-client-content-negotiation + ktor-client-cookies** — HTTP client for Oda
- **org.xerial:sqlite-jdbc** — SQLite
- **com.github.ajalt.clikt:clikt** — CLI args
- **ch.qos.logback:logback-classic** — logging

That's it. Don't add anything else unless absolutely necessary.

Entry point: accept `--email` and `--password` as required options, `--port` as optional (default 8080). Use Clikt.

## Phase 2: SQLite schema

Create the database on startup if it doesn't exist. Use raw SQL with sqlite-jdbc. Wrap database access in a simple repository class — no framework, just functions that take a Connection and return data classes.

```sql
products (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  oda_product_id INTEGER UNIQUE,
  name TEXT NOT NULL,
  category TEXT,
  product_type TEXT              -- groups product variants (e.g. all banana SKUs → "Banan")
)

meals (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL
)

meal_ingredients (
  meal_id INTEGER REFERENCES meals(id),
  product_id INTEGER REFERENCES products(id),
  quantity REAL,
  unit TEXT,
  PRIMARY KEY (meal_id, product_id)
)

orders (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  oda_order_id TEXT UNIQUE NOT NULL,
  ordered_at TEXT NOT NULL,       -- raw date from Oda (Norwegian format, e.g. "fre 20. mars, 18:59")
  ordered_at_iso TEXT             -- parsed to ISO 8601 for calculations
)

order_items (
  order_id INTEGER REFERENCES orders(id),
  product_id INTEGER REFERENCES products(id),
  quantity INTEGER NOT NULL,
  PRIMARY KEY (order_id, product_id)
)

meal_plans (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  planned_at TEXT NOT NULL,
  week_start TEXT NOT NULL
)

meal_plan_entries (
  meal_plan_id INTEGER REFERENCES meal_plans(id),
  meal_id INTEGER REFERENCES meals(id),
  day TEXT,
  is_new_suggestion INTEGER DEFAULT 0
)

plan_feedback (
  meal_plan_id INTEGER REFERENCES meal_plans(id),
  product_id INTEGER REFERENCES products(id),
  suggested_quantity INTEGER,
  ordered_quantity INTEGER
)
```

## Phase 3: Oda API client

Write a Kotlin HTTP client using Ktor Client that authenticates with Oda and provides all the operations the agent needs. Look at the oda-mcp source (locally available) and the Python client linked above for endpoint details, response shapes, and auth flow.

### Auth flow:
1. GET `https://oda.com/no/user/login/` — picks up `csrftoken` cookie
2. POST `https://oda.com/api/v1/user/login/` with JSON body `{"username": email, "password": password}` and header `x-csrftoken` from cookie
3. Keep cookies in Ktor's in-memory cookie storage, reuse for all subsequent requests

### Required headers on all requests:
```
user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36
origin: https://oda.com
referer: https://oda.com/
x-client-app: tienda-web
x-country: no
x-language: nb
x-requested-case: camel
```

### Endpoints to implement:
- `GET /api/v1/orders?through-date={ISO-date}` — list orders, paginated via `through-date` param. Response contains `hasMore` and `getMoreUrl` for next page
- `GET /api/v1/orders/{id}` — order details with line items
- `GET /api/v1/cart/` — current cart
- `POST /api/v1/cart/items/?group_by=recipes` — add to cart (body: `{"items": [{"product_id": id, "quantity": qty}]}`)
- `DELETE` or set quantity to -1 to remove from cart
- `GET /api/v1/search/mixed/?q={term}&type=product&size=50` — search products
- `GET /api/v1/user/refresh/` — verify session is still valid

### Order sync logic:
- Run on startup, then every 6 hours via a coroutine with `while(true) { delay(6.hours) }`
- Fetch order list page, for each order not already in SQLite, fetch details and upsert
- Follow `hasMore`/`getMoreUrl` for pagination. Support a `maxPages` config to limit full history fetches
- Add 3-6 second random delay between pages, 4-8 seconds between order detail fetches
- Products come from order details — `itemGroups` with `type: "category"` contain items and a category `name`
- Parse Norwegian dates to ISO 8601 on upsert (Oda returns "fre 20. mars, 18:59" — no year, must be inferred)
- Classify `product_type` for new products on upsert. Match against existing types first; for genuinely new products, use LLM classification to assign a type consistent with existing ones
- Must be idempotent — safe to run any number of times

Only model the fields we actually need from Oda responses. Use kotlinx.serialization with `ignoreUnknownKeys = true`.

## Phase 4: HTTP endpoints

These are the endpoints the AI agent will call. All responses are JSON.

### Oda proxy endpoints (agent uses these to interact with Oda):
```
GET  /search?q={term}      — search Oda products
GET  /cart                  — current Oda cart
POST /cart/items            — add item to cart (body: {"productId": 123, "quantity": 1})
DELETE /cart/items/{id}     — remove item from cart
```

### Analysis endpoints:
```
GET  /stats                 — per-product_type feature profiles for LLM consumption
```

Returns the top ~50 product types sorted by urgency (most overdue first). Only includes types with 5+ orders and filters out dormant items (urgency > 4).

Each entry contains these features:

- `product_type` — grouping name
- `order_count` — number of orders containing this type
- `freq_all_time` — fraction of all orders (0-1)
- `weighted_freq` — time-decayed frequency (exponential decay, half-life ~90 days)
- `freq_recent_30d` — orders in last 30 days
- `avg_gap_days` — mean days between purchases
- `avg_gap_per_unit` — **quantity-normalized**: gap_days / quantity bought. Buying 2 packs doubles the expected interval. This is the key cycle indicator
- `weighted_avg_gap` — recency-weighted version of above
- `cv` — coefficient of variation (stddev/mean of intervals). Low (<0.5) = predictable, high (>0.8) = erratic
- `days_since_last` — days since last purchase
- `last_order_date` — date of last purchase
- `last_qty` — quantity in last order
- `avg_qty_per_order` — typical quantity
- `urgency` — `days_since_last / (avg_gap_per_unit × last_qty)`. >1.0 = overdue

Do NOT apply rigid classification (e.g. FAST/VARIABEL). The agent LLM reasons better from raw features than from pre-bucketed labels. Threshold tuning proved brittle in PoC testing.

### Meal planning endpoints:
```
GET  /meals                 — all registered meals with ingredients (grouped by meal, product_type per ingredient)
POST /meals                 — add a meal (body: {"name": "Taco", "ingredients": [{"productId": 123, "quantity": 1, "unit": "pkg"}]})
GET  /meal-plans?weeks=4    — recent meal plans
POST /meal-plans            — save a weekly meal plan
GET  /plan-feedback         — diff between suggested plans and actual orders
```

### Expected agent workflow for generating a shopping list:

The agent should follow this two-step flow:

1. **Meal planning**: Call `GET /meals` → select 5-7 dinners for the week → build ingredient list
2. **Replenishment**: Call `GET /stats` → build replenishment list, **excluding product_types already covered by the meal ingredient list**
3. **Combine**: Merge both lists into a single shopping list grouped by category

This separation avoids double-counting (e.g. brokkoli appearing both as a dinner ingredient and a replenishment item).

## Phase 5: Background sync and lifecycle

On startup:
1. Parse CLI args (email, password, port)
2. Initialize SQLite (create tables if needed)
3. Authenticate with Oda
4. Run initial order sync
5. Launch background coroutine for periodic sync
6. Start Ktor HTTP server

Graceful shutdown: close database connection and HTTP client.

## Code organization

Keep it flat and simple. Something like:

```
src/main/kotlin/
  Main.kt              — Clikt command, wires everything together
  OdaClient.kt         — Ktor client for Oda API
  Database.kt          — SQLite setup and repository functions
  Routes.kt            — Ktor route definitions
  Analysis.kt          — Feature profile calculations (intervals, CV, urgency, etc.)
  Models.kt            — Data classes for domain objects and API responses
```

No DI framework. No abstract factories. Just pass dependencies as constructor parameters.

## Development approach

Start with phases 1-3 and verify that order sync works against the live Oda API before building the HTTP endpoints. Stop after phase 3 and confirm data is in SQLite before proceeding.
