// The README's screenshots (docs/screenshots/): `npm run screenshots` makes a demo shop with
// products, customers and orders at every stage, then photographs each page in Bangla, at laptop
// and phone sizes. It needs the test backend and the built app running, as for `npm run e2e`:
//
//   (cd ../backend && ./mvnw spring-boot:test-run)          the test backend, on port 8080
//   npm run build && npx vite preview --port 4173            the built app
//
// Another address: SCREENSHOTS_URL=http://localhost:4173. Only for the test backend: it signs up
// through /dev/otp and puts the shop on Pro through /dev/subscription.
import { mkdirSync } from "node:fs";
import path from "node:path";
import { chromium } from "@playwright/test";

const BASE = process.env.SCREENSHOTS_URL ?? "http://localhost:4173";
const OUT = path.resolve(import.meta.dirname, "../../docs/screenshots");
const CONTEXT = { baseURL: BASE, locale: "bn-BD", timezoneId: "Asia/Dhaka", serviceWorkers: "block" };
const LAPTOP = { ...CONTEXT, viewport: { width: 1440, height: 900 } };
const PHONE = {
  ...CONTEXT,
  viewport: { width: 390, height: 844 },
  deviceScaleFactor: 2,
  isMobile: true,
  hasTouch: true,
};

mkdirSync(OUT, { recursive: true });
const browser = await chromium.launch();
const desk = await browser.newContext(LAPTOP);
const api = desk.request;

async function xsrf() {
  await api.get("/api/v1/auth/csrf");
  return (await desk.cookies()).find((cookie) => cookie.name === "XSRF-TOKEN")?.value ?? "";
}

async function post(url, data, extra = {}) {
  const answer = await api.post(url, {
    headers: { "X-XSRF-TOKEN": await xsrf(), "Accept-Language": "bn" },
    data,
    ...extra,
  });
  if (!answer.ok()) {
    throw new Error(`POST ${url}: ${answer.status()} ${await answer.text()}`);
  }
  const text = await answer.text();
  return text ? JSON.parse(text) : {};
}

/** A Bangladeshi mobile number nobody in the test database has yet. */
const newPhone = (prefix) => prefix + String(Math.floor(Math.random() * 1e8)).padStart(8, "0");

// The owner and the shop.
const phone = newPhone("017");
await post("/api/v1/auth/otp", { to: phone, purpose: "SIGNUP" });
let code = null;
for (let tries = 0; tries < 50 && !code; tries++) {
  const answer = await api.get(`/dev/otp?to=${phone}`);
  if (answer.ok()) {
    code = (await answer.json()).code;
  } else {
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
}
await post("/api/v1/auth/signup", {
  userId: `rahima${Math.floor(Math.random() * 1e4)}`,
  name: "রহিমা আক্তার",
  phone,
  password: `demo-${crypto.randomUUID()}`,
  codeSentTo: "PHONE",
  code,
});
const business = await post("/api/v1/businesses", { name: "রহিমার কুর্তি হাউস" });
await post(`/dev/subscription?businessId=${business.id}&status=ACTIVE&plan=PRO&days=365`);

// Product pictures: an emoji on a soft gradient, drawn in the browser.
const painter = await desk.newPage();
async function picture(emoji, from, to) {
  const url = await painter.evaluate(
    ([emoji, from, to]) => {
      const canvas = document.createElement("canvas");
      canvas.width = canvas.height = 600;
      const g = canvas.getContext("2d");
      const gradient = g.createLinearGradient(0, 0, 600, 600);
      gradient.addColorStop(0, from);
      gradient.addColorStop(1, to);
      g.fillStyle = gradient;
      g.fillRect(0, 0, 600, 600);
      g.globalAlpha = 0.12;
      g.fillStyle = "#fff";
      for (let y = 30; y < 600; y += 60) {
        for (let x = (y / 60) % 2 ? 60 : 30; x < 600; x += 60) {
          g.beginPath();
          g.arc(x, y, 6, 0, 7);
          g.fill();
        }
      }
      g.globalAlpha = 1;
      g.font = "300px 'Noto Color Emoji'";
      g.textAlign = "center";
      g.textBaseline = "middle";
      g.fillText(emoji, 300, 320);
      return canvas.toDataURL("image/png");
    },
    [emoji, from, to],
  );
  return Buffer.from(url.split(",")[1], "base64");
}

const categories = {};
for (const name of ["কুর্তি", "শাড়ি", "পাঞ্জাবি", "ওড়না ও হিজাব", "গয়না", "ব্যাগ"]) {
  categories[name] = (await post("/api/v1/categories", { name })).id;
}

// Name, category, picture, and its variants: colour, size, price, cost (taka), on hand.
const catalog = [
  [
    "সুতি কুর্তি — ব্লক প্রিন্ট",
    "কুর্তি",
    ["👗", "#fde2e4", "#fad2e1"],
    [
      ["নীল", "M", 1250, 720, 14],
      ["নীল", "L", 1250, 720, 9],
      ["লাল", "M", 1300, 750, 3],
      ["লাল", "L", 1300, 750, 0],
    ],
  ],
  [
    "জামদানি শাড়ি",
    "শাড়ি",
    ["🥻", "#e2ece9", "#bee1e6"],
    [
      ["অফ-হোয়াইট", null, 6500, 4200, 4],
      ["মেরুন", null, 6800, 4400, 2],
    ],
  ],
  [
    "তাঁতের শাড়ি",
    "শাড়ি",
    ["🥻", "#fff1e6", "#f0d9c8"],
    [
      ["হলুদ", null, 2200, 1350, 11],
      ["সবুজ", null, 2200, 1350, 7],
    ],
  ],
  [
    "ঈদ পাঞ্জাবি — সিল্ক",
    "পাঞ্জাবি",
    ["👔", "#dfe7fd", "#cddafd"],
    [
      [null, "40", 2450, 1500, 6],
      [null, "42", 2450, 1500, 8],
      [null, "44", 2450, 1500, 2],
    ],
  ],
  [
    "জর্জেট হিজাব",
    "ওড়না ও হিজাব",
    ["🧕", "#f1e4f3", "#e2cfea"],
    [
      ["কালো", null, 450, 210, 40],
      ["বেগুনি", null, 450, 210, 22],
      ["ধূসর", null, 450, 210, 1],
    ],
  ],
  ["কাশ্মীরি ওড়না", "ওড়না ও হিজাব", ["🧣", "#fef3c7", "#fde68a"], [[null, null, 850, 480, 18]]],
  ["অক্সিডাইজড ঝুমকা", "গয়না", ["💍", "#ede9fe", "#ddd6fe"], [[null, null, 350, 140, 55]]],
  [
    "পাটের হ্যান্ডব্যাগ",
    "ব্যাগ",
    ["👜", "#ecfccb", "#d9f99d"],
    [
      ["প্রাকৃতিক", null, 1100, 600, 12],
      ["কালো", null, 1150, 620, 5],
    ],
  ],
  [
    "বাচ্চাদের ফ্রক",
    "কুর্তি",
    ["👚", "#fce7f3", "#fbcfe8"],
    [
      [null, "২-৩ বছর", 780, 420, 10],
      [null, "৪-৫ বছর", 820, 440, 3],
    ],
  ],
];
const products = [];
for (const [name, category, look, rows] of catalog) {
  const product = await post("/api/v1/products", {
    name,
    categoryId: categories[category],
    description: "নিজস্ব ডিজাইন, আরামদায়ক কাপড়। ধোয়ার পরও রং ঠিক থাকে।",
    variants: rows.map(([colour, size, price, cost, quantity]) => ({
      name: colour,
      size,
      price: price * 100,
      costPrice: cost * 100,
      quantity,
    })),
  });
  const file = await post("/api/v1/uploads", undefined, {
    multipart: { file: { name: "product.png", mimeType: "image/png", buffer: await picture(...look) } },
  });
  await post(`/api/v1/products/${product.id}/images`, { fileId: file.id });
  products.push(product);
}
const variant = (product, row = 0) => products[product].variants[row].id;

// Name, address, district and area (V18__geo.sql: 47 Dhaka; 9002 Badda, 9013 Dhanmondi, ...).
const people = [
  ["নুসরাত জাহান", "বাসা ১২, রোড ৫, ধানমন্ডি", 47, 9013],
  ["ফারহানা ইসলাম", "সেক্টর ৭, উত্তরা", 47, 9047],
  ["তাসলিমা বেগম", "মিরপুর ১০, ব্লক সি", 47, 9027],
  ["সাবরিনা হক", "আগ্রাবাদ, চট্টগ্রাম", 8, null],
  ["মাহমুদা খাতুন", "কান্দিরপাড়, কুমিল্লা", 1, null],
  ["রুমানা আফরোজ", "বাড্ডা লিংক রোড", 47, 9002],
  ["শারমিন সুলতানা", "মোহাম্মদপুর, তাজমহল রোড", 47, 9028],
  ["জান্নাতুল ফেরদৌস", "চাষাড়া, নারায়ণগঞ্জ", 43, null],
];
const prefixes = ["018", "019", "016", "015", "017"];
const customers = [];
for (const [index, [name, address, districtId, areaId]] of people.entries()) {
  const customerPhone = newPhone(prefixes[index % prefixes.length]);
  customers.push(await post("/api/v1/customers", { name, phone: customerPhone, address, districtId, areaId }));
}

/** An order for this customer of [product, variant row, quantity] items, taken through these steps. */
async function order(customer, items, steps = [], { ship, ...extra } = {}) {
  const made = await post("/api/v1/orders", {
    customerId: customers[customer].id,
    items: items.map(([product, row, quantity]) => ({ variantId: variant(product, row), quantity })),
    deliveryCharge: people[customer][2] === 47 ? 6000 : 12000,
    ...extra,
  });
  for (const step of steps) {
    await post(`/api/v1/orders/${made.id}/${step}`, step === "ship" ? ship : step === "deliver" ? {} : undefined);
  }
  return made;
}
const pathao = (number) => ({ courier: "Pathao", trackingNo: `PTH-${number}`, courierCharge: 6000 });
const steadfast = (number) => ({ courier: "Steadfast", trackingNo: `SF${number}`, courierCharge: 11000 });
const delivered = ["confirm", "ship", "deliver"];

await order(
  0,
  [
    [0, 0, 2],
    [6, 0, 2],
  ],
  delivered,
  { ship: pathao("88231"), advancePaid: 20000, advanceMethod: "BKASH", advanceReference: "8N7X2KQ1" },
);
await order(1, [[1, 0, 1]], delivered, { ship: pathao("88247"), advancePaid: 100000, advanceMethod: "NAGAD" });
await order(
  3,
  [
    [2, 0, 1],
    [5, 0, 1],
  ],
  delivered,
  { ship: steadfast("4412093") },
);
await order(2, [[3, 1, 1]], delivered, { ship: pathao("88302") });
await order(
  5,
  [
    [4, 0, 3],
    [4, 1, 2],
  ],
  ["confirm", "ship"],
  { ship: pathao("88315") },
);
await order(4, [[7, 0, 1]], ["confirm", "ship"], { ship: steadfast("4412377") });
await order(
  7,
  [
    [8, 0, 1],
    [6, 0, 1],
  ],
  ["confirm", "ship"],
  { ship: steadfast("4412410"), advancePaid: 10000, advanceMethod: "BKASH" },
);
await order(
  6,
  [
    [0, 1, 1],
    [5, 0, 1],
  ],
  ["confirm", "pack"],
);
await order(0, [[2, 1, 2]], ["confirm", "pack"]);
await order(
  1,
  [
    [3, 0, 1],
    [6, 0, 3],
  ],
  ["confirm"],
);
const featured = await order(
  2,
  [
    [0, 0, 1],
    [4, 1, 2],
    [6, 0, 1],
  ],
  ["confirm"],
  {
    advancePaid: 50000,
    advanceMethod: "BKASH",
    advanceReference: "9C4K7TQ2",
    note: "সন্ধ্যার পর ফোন দেবেন",
  },
);
await order(5, [[7, 1, 1]]);
await order(3, [[1, 1, 1]], [], { note: "ঈদের আগে পৌঁছাতে হবে" });
const cancelled = await order(6, [[8, 1, 2]]);
await post(`/api/v1/orders/${cancelled.id}/cancel`, { reason: "ক্রেতা অর্ডার বাতিল করেছেন" });

await post("/api/v1/stock/adjustments", {
  lines: [
    { variantId: variant(0, 2), kind: "DAMAGE", quantity: 1 },
    { variantId: variant(4, 2), kind: "DAMAGE", quantity: 1 },
  ],
  note: "ধোয়ার পর রং উঠে গেছে",
  reason: "STAIN",
});

// The pictures.
async function photograph(page, name, url) {
  await page.goto(url);
  await page.waitForLoadState("networkidle");
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(400);
  await page.screenshot({ path: path.join(OUT, `${name}.png`) });
  console.log(`docs/screenshots/${name}.png`);
}

const signedOut = await (await browser.newContext(LAPTOP)).newPage();
await photograph(signedOut, "sign-in", "/sign-in");

const laptop = await desk.newPage();
for (const [name, url] of [
  ["today", "/"],
  ["products", "/products/"],
  ["product", `/products/${products[0].id}`],
  ["new-order", "/orders/new"],
  ["orders", "/orders/"],
  ["order", `/orders/${featured.id}/`],
  ["customers", "/customers/"],
  ["cod", "/cod/"],
  ["reports", "/reports/"],
  ["low-stock", "/products/?lowStock=true"],
  ["damages", "/damages/"],
  ["billing", "/billing/"],
]) {
  await photograph(laptop, name, url);
}

const phoneView = await (await browser.newContext({ ...PHONE, storageState: await desk.storageState() })).newPage();
for (const [name, url] of [
  ["phone-today", "/"],
  ["phone-orders", "/orders/"],
  ["phone-order", `/orders/${featured.id}/`],
]) {
  await photograph(phoneView, name, url);
}

await browser.close();
