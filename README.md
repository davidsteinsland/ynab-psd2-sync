# Enable Banking (PSD2)

Henter banktransaksjoner via [Enable Banking API](https://enablebanking.com/docs/).
Gratis for personlig bruk. Støtter alle norske banker inkl. Sparebanken Norge.

## Daglig sync 

Jeg bruker 1Passwords `op` slik at hemmeligheter ikke ligger som filer på disk:
```
op run --env-file=.env -- ./gradlew run --args="--state .state.json"
op run --env-file=.env -- ./gradlew run --args="--state .state-person2.json"
```

Ting lastes opp til YNAB etterpå i eget steg, etter at man har hentet transaksjoner man trenger:
```
op run --env-file=.env -- ./gradlew run --args="--sync-ynab"
```

## Varsling ved utløpt / snart utløpt samtykke

Bruk tjenesten [ntfy.sh](https://ntfy.sh/) for å sende push-varsler til telefonen når samtykke må fornyes (etter 180 dager).
Sett da miljøvariabelen `NTFY_TOPIC` i `.env`-filen.

## Tips

Kjør sync-jobbene jevnlig med f.eks. `systemd` på Linux, med helsesjekk-rapportering til [HealthChecks.io](https://healthchecks.io/)

```
# psd2-sync.service
[Unit]
Description=PSD2 sync: fetch transactions
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
WorkingDirectory=/var/docker-services/ynab-psd2-sync
ExecStart=./gradlew --state .state.json
ExecStartPost=/usr/bin/curl -fsS --retry 3 --max-time 10 https://hc-ping.com/randomuuid
TimeoutStartSec=10min

# psd2-sync.timer
[Unit]
Description=PSD2 sync: fetch transactions (twice on weekdays)

[Timer]
OnCalendar=Mon..Fri *-*-* 07:00:00
OnCalendar=Mon..Fri *-*-* 14:00:00
RandomizedDelaySec=2min
Persistent=true

[Install]
WantedBy=timers.target
```

... og en tilsvarende for push til YNAB.

## Oppsett

1. Lag bruker på https://enablebanking.com → "Get started"
2. Opprett applikasjon på https://enablebanking.com/cp/applications
   - Generer en privatnøkkel og last opp sertifikatet (PEM-format)
   - Kopier applikasjons-ID
   - Sett en `redirect_url` (f.eks. `https://localhost/callback` for lokal bruk)
   - "Link Accounts" og logg på med BankID på de bankene du vil hente transaksjoner fra
3. Lagre privatnøkkel og applikasjonsID som hemmeligheter i 1Password (se `.env` for hvordan de skal importeres runtime)

4. Finn ASPSP-navnet for banken din:

   ```sh
   op run --env-file=.env -- ./gradlew run --args="--list-aspsps"
   ```

5. Start samtykke (180 dagers tilgang):

   ```sh
   op run --env-file=.env -- ./gradlew run --console=plain --args="--init"
   ```

   Du får en URL → logg inn med BankID → lim inn redirect-URL-en tilbake i
   terminalen. Sesjons-ID lagres i `.state.json` (overstyr med `--state <fil>`
   hvis flere brukere deler maskinen og skal ha hver sin sesjon, f.eks.
   `--state .state-person2.json`). State-filer blir ignorert via `.gitignore`.

6. Hent transaksjoner (siste 7 dager):

   ```sh
   op run --env-file=.env -- ./gradlew run
   ```
   
   Angi en annen statefil via argument: `--args="--state .state-person2.json"` til `gradlew`-kommandoen over.

   Det vil bli produsert json-filer i `extracted`-mappa for hver konto:
   - `extracted/<konto-uuid>.json`

## Push direkte til YNAB

1. Lag en Personal Access Token: app.ynab.com → Account Settings → Developer Settings → New Token.

2. Map bank-kontoer til YNAB-kontoer (interaktivt – velg budsjett først, deretter
   en YNAB-konto per bank-konto):

   ```sh
   op run --env-file=.env -- ./gradlew run --args="--map-accounts"
   ```

   Mapping og budsjett-ID lagres i `.ynab.json`. Re-init beholder
   eksisterende mapping. 

   Viktig: Kjør `--map-accounts` på nytt for å legge til nye kontoer.
   YNAB-mappingen er felles for alle state-filer fordi det er en antagelse at alle transaksjoner skal pushes til samme YNAB-budsjett.

3. Push til YNAB:

   ```sh
   op run --env-file=.env -- ./gradlew run --args="--sync-ynab"
   ```

   Kun BOOK-transaksjoner pushes (pending ekskluderes).

   YNAB matcher på `import_id` – samme transaksjon kan pushes flere ganger uten
   duplikater. `import_id` settes til `EB:<entry_reference>` når banken oppgir
   det, ellers til en stabil SHA-256-hash av (dato, beløp, payee, memo) (`EB:` + 33 tegn fra base64 av hash).
