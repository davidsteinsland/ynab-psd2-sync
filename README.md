# Enable Banking (PSD2)

Henter banktransaksjoner via [Enable Banking API](https://enablebanking.com/docs/).
Gratis for personlig bruk. Støtter alle norske banker inkl. Sparebanken Norge.


## Daglig sync 

```
YNAB_EB_STATE_FILE=~/.ynab-enablebanking.json op run --env-file=.env -- ./gradlew run
YNAB_EB_STATE_FILE=~/.ynab-enablebanking-ida.json op run --env-file=.env -- ./gradlew run

op run --env-file=.env -- ./gradlew run --args="--sync-ynab"
```

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
   terminalen. Sesjons-ID lagres i `~/.ynab-enablebanking.json` (overstyr med
   miljøvariabelen `YNAB_EB_STATE_FILE` hvis flere brukere deler maskinen og
   skal ha hver sin sesjon).

6. Hent transaksjoner (siste 7 dager):

   ```sh
   op run --env-file=.env -- ./gradlew run
   ```

   Resultat:
   - `extracted/<konto-uuid>.json`

## Push direkte til YNAB

1. Lag en Personal Access Token: app.ynab.com → Account Settings → Developer Settings → New Token.

2. Map bank-kontoer til YNAB-kontoer (interaktivt – velg budsjett først, deretter
   en YNAB-konto per bank-konto):

   ```sh
   op run --env-file=.env -- ./gradlew run --args="--map-accounts"
   ```

   Mapping og budsjett-ID lagres i `.ynab.json`. Re-init beholder
   eksisterende mapping. Kjør `--map-accounts` på nytt for å legge til nye kontoer.

3. Push til YNAB:

   ```sh
   op run --env-file=.env -- ./gradlew run --args="--sync-ynab"
   ```

   Kun BOOK-transaksjoner pushes (pending ekskluderes).

   YNAB matcher på `import_id` – samme transaksjon kan pushes flere ganger uten
   duplikater. `import_id` settes til `EB:<entry_reference>` når banken oppgir
   det, ellers til en stabil SHA-256-hash av (dato, beløp, payee, memo) (`EB:` + 33 tegn fra base64 av hash).
