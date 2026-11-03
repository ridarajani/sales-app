# Going live

The steps to put the app on a live server, load the client's real lists, and get staff using it. Do them in this order.

Everything secret stays off GitHub: `deploy/.env`, the backups, the logos, the Android signing key, `mobile/local.properties`, `mobile/keystore.properties` and `mobile/iosApp/Configuration/Local.xcconfig`.

## 1. The server

- **A rented cloud server** (recommended): Ubuntu 24.04, 2 CPUs, 4 GB memory, 80 GB disk is plenty for the four companies. Any provider works; pick one with a data centre close to Malawi (South Africa or Europe).
- **Or a computer in the office**: the same steps work, but it needs a fixed public internet address, ports 80 and 443 opened on the router, power backup, and someone to keep it on. Phones in the field reach it over the internet either way.

Buy a domain (or use one the client has) and add a DNS **A record**, for example `sales.yourdomain.com`, pointing at the server's internet address. Wait until `ping sales.yourdomain.com` shows that address.

## 2. Start the app on the server

Sign in to the server and run:

```sh
# Docker
curl -fsSL https://get.docker.com | sh

# The app
git clone https://github.com/ridarajani/sales-app.git
cd sales-app/deploy
cp .env.example .env
nano .env
```

Fill in `.env`:

- `DOMAIN`: the address from step 1, for example `sales.yourdomain.com`.
- `POSTGRES_PASSWORD`: a long random password. `openssl rand -hex 24` makes one.
- `BOOTSTRAP_ADMIN_PHONE` and `BOOTSTRAP_ADMIN_PIN`: your own phone number and a PIN only you know. This is the first Admin login.
- `KEEP_DAYS`: how many days of backups to keep on the server (30 is fine).

Then start it:

```sh
docker compose up -d --build
docker compose ps                       # all four should say "running" (server: "healthy")
curl https://sales.yourdomain.com/health   # {"ok":true}
```

The HTTPS certificate is made by itself the first time; if `/health` fails, wait a minute and check that the DNS record is right (`docker compose logs caddy`).

## 3. Company details and logos

1. Open `https://sales.yourdomain.com/office/` on a computer and sign in with the Admin phone and PIN.
2. In the phone app, as the Admin, open **Companies** and fill in each company's TPIN, VAT rate, phone, email and address. They are printed on the VAT invoices.
3. Copy the logos onto the server, named by company code (`LV.png`, `ZT.png`, `ZF.png`, `GL.png`, or `.jpg`):

   ```sh
   docker compose cp ~/logos/LV.png server:/app/branding/LV.png
   ```

## 4. The client's lists

On the office website, open **Import from Excel**. For each list: download the template, fill it in, press **Check the file**, fix any row it shows, then press **Save**. Nothing is saved until every row is right.

1. **Customers** (Admin or Accounts)
2. **Products** (Admin or Accounts)
3. **Opening balances**: what each customer owed on the start day (Admin or Accounts). Import the customers first.
4. **Opening stock**: what is in each warehouse on the start day (Admin or Warehouse). Import the products first. Each company gets one goods received note called "Opening stock".

Then, in the phone app as the Admin:

5. Add the vehicles, with their warehouse and usual driver.
6. Add the staff: name, phone, job, companies, and a first PIN each. Sales managers can add their own salesmen.
7. Set the credit limits, low-stock levels and monthly targets where the client wants them.

## 5. Phones

### Android (field staff)

1. On the Mac, set the live address and the signing key as in the README (**Release builds**), then build the release app (`./gradlew :androidApp:assembleRelease`). Keep the signing key file and its password safe and off GitHub: every later update must be signed with the same key, or phones will refuse it.
2. Put it on the server:

   ```sh
   cd sales-app/deploy
   bash add-apk.sh ~/sales-1.0.0-release.apk 2 1.0.0 "First version"
   ```

   The second number is the version code. Each new version needs a bigger one than the last.
3. Staff open `https://sales.yourdomain.com/download` on their phone, tap **Download**, and allow installing from the browser when Android asks.
4. For later versions, run `add-apk.sh` again with a bigger version code. The app offers the update the next time it opens.

### iPhone (management)

There is no paid Apple account, so managers install the app for free with **SideStore** and their own Apple ID. A free install lasts 7 days and SideStore renews it on the phone by itself while LocalDevVPN is connected.

1. On the Mac, set `SALES_API_HOST = sales.yourdomain.com` in `mobile/iosApp/Configuration/Local.xcconfig` (no `https://`), then run `bash mobile/iosApp/make-ipa.sh`. It puts `SalesApp.ipa` on the Desktop.
2. For each manager's iPhone (iOS 15 or newer), once, with the phone plugged into the Mac: install **LocalDevVPN** from the App Store; on the Mac, use **iloader** (iloader.app) to sign in with the manager's Apple ID and install SideStore; on the phone, trust the developer in **Settings > General > VPN & Device Management**, turn on **Developer Mode**, connect LocalDevVPN, and sign in to SideStore.
3. AirDrop `SalesApp.ipa` to the phone and add it in SideStore with **+** under **My Apps**.
4. For later versions, raise the version, run `make-ipa.sh` again and add the new file in SideStore the same way. The sign-in is kept.

A free Apple ID allows 3 such apps on a phone (SideStore included). If this becomes too much work, a US$99-a-year Apple Developer account replaces it with TestFlight.

## 6. Test run and training

1. Do one full day with a few real staff before everyone starts: an order, loading, delivery with signature, payment, a return, a minibus trip, and the day's reports.
2. Check the numbers with Accounts: the opening balances and stock should match their books.
3. Train each job on its own few screens. Each person only sees their own companies and their own job's tiles.
4. Change the Admin PIN from the one in `.env` once you are in, and give each person their own PIN.

## 7. Backups

The backup container saves the database and the saved files (signatures, photos, logos, app files) every night at 02:00 Malawi time into `deploy/backups`, keeping `KEEP_DAYS` days.

- **Copy them off the server** too, so a dead server does not take the backups with it. For example, from your computer each week: `scp -r root@sales.yourdomain.com:sales-app/deploy/backups ~/sales-backups`.
- **A backup now**: `docker compose exec backup bash /scripts/backup.sh`
- **Put a backup back** (replaces everything on the live server; it asks you to type RESTORE):

  ```sh
  bash restore.sh backups/sales-2026-11-02-0200.dump backups/files-2026-11-02-0200.tar.gz
  ```

Try a restore once on a spare server before go-live, so you know it works.

## 8. Updating the server

When a new phase is merged:

```sh
cd sales-app && git pull && cd deploy && docker compose up -d --build
```

Database changes run by themselves when the server starts. Updates take about a minute; do them outside working hours.

## If something goes wrong

- `docker compose ps` shows what is running; `docker compose logs server --tail 100` shows the server's last messages.
- `docker compose restart server` restarts the app without touching the data.
- The office website's **Alerts** page shows what needs attention in the business; the server logs show what is wrong with the server.
