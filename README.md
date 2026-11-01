# Android TV — debloat reversibile + home "Essenziale"

Tutto gira **dal tuo computer** (Windows, macOS o Linux) collegato alla stessa rete Wi-Fi della TV.
Niente viene mai disinstallato: le app di sistema vengono solo *disabilitate* (`pm disable-user`) e ogni
modifica è registrata con il suo comando inverso.

## Preparazione (una volta)
1. **Python 3** e **adb**: scarica le [SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools)
   ed estrai la cartella `platform-tools` dentro `toolkit/` (oppure metti `adb` nel PATH).
2. Sulla TV: *Impostazioni › Sistema › Info* → premi 7 volte su *Build* → nelle *Opzioni sviluppatore*
   attiva **Debug USB** e **Debug di rete/ADB** (o **Debug wireless** su Android 11+).

## Uso
```bash
cd toolkit
python3 tv.py tutto        # trova la TV, backup, debloat guidato, installa la home
```
Oppure passo per passo:

| Comando | Cosa fa |
|---|---|
| `python3 tv.py trova` | Scansiona la rete: ADB (5555), debug wireless (mDNS), TV con Chromecast (8008) e ne legge il nome |
| `python3 tv.py connetti 192.168.1.50` | Connette (sulla TV conferma «Consenti sempre») |
| `python3 tv.py abbina IP:PORTA CODICE` | Abbinamento per il *Debug wireless* di Android 11+ |
| `python3 tv.py backup` | Nuovo backup completo (pacchetti, impostazioni, home, screenshot) |
| `python3 tv.py piano` | Mostra cosa *verrebbe* disabilitato, senza toccare nulla |
| `python3 tv.py debloat` | Debloat guidato con conferme |
| `python3 tv.py launcher` | Installa Essenziale, scegli le preferite, la imposta come home e verifica |
| `python3 tv.py screenshot nome` | Screenshot dalla TV nella cartella del backup |
| `python3 ripristina.py` | **Ripristino completo** dall'ultimo backup |

### Livelli del debloat (`toolkit/catalogo.json`)
- **sicuro** – pubblicità e telemetria: un'unica conferma per il gruppo.
- **consigliato** – contenuti promossi, salvaschermo con promo, servizi inutili: una domanda per voce (default Sì).
- **facoltativo** – app/funzioni che potresti usare (Assistant, Chromecast integrato, YouTube, Live Channels…): una domanda per voce (default **No**).
- **da valutare** – pacchetti fuori catalogo con nomi sospetti (`ads`, `promo`, `analytics`…): default No.
- Mai toccati: Play Store, Google Play Services, impostazioni, tastiera, telecomando, provider di sistema, setup.

Dopo ogni gruppo lo script preme HOME, verifica che la TV risponda (altrimenti **annulla da solo** il gruppo)
e salva uno screenshot. Senza terminale interattivo non disabilita nulla, a meno di indicare i pacchetti
esatti con `--solo pkg1,pkg2`.

### Backup e ripristino
Ogni backup è in `toolkit/backup/<data>_<modello>/` e contiene: `stato.json`, elenchi pacchetti,
`impostazioni_*.txt`, `registro.jsonl` (ogni azione con il suo inverso), `screenshot/`, `rapporto.html`
(azioni + screenshot) e due script autonomi aggiornati dopo ogni modifica:
- `ripristino.sh` (macOS/Linux) / `ripristino.bat` (Windows): bastano adb, non serve Python.
- oppure `python3 ripristina.py [cartella] [--tieni-launcher]`, che inoltre riabilita *qualsiasi* pacchetto
  attivo al momento del backup e ripristina la home originale.

## La home "Essenziale" (`launcher/`)
- Sfondo naturalistico a tutto schermo con dissolvenza (default ogni 90 s; tasto ⏩ = sfondo successivo).
  Foto "in vetrina" di Wikimedia Commons (paesaggi, montagne, laghi, foreste, cascate, coste) scaricate dalla TV
  e messe in cache, con autore e licenza in basso a destra. Con `--sfondi CARTELLA` usa invece le tue foto.
- Orologio e data, una sola fila di **preferite** (banner 16:9 delle app), *Tutte le app*, *Impostazioni*.
- OK apre · tieni premuto OK (o MENU) su una preferita: sposta/togli · nella griglia: aggiungi/togli preferita.
- Nessuna pubblicità, nessun tracciamento, nessuna libreria esterna: APK da ~40 KB, solo Java/Android SDK.

`toolkit/Essenziale.apk` è già compilato. Per ricompilarlo (Ubuntu/Debian):
```bash
sudo apt install aapt android-sdk-platform-23 dalvik-exchange apksigner zipalign default-jdk
launcher/build.sh
```
La chiave di firma è in `launcher/keystore/` (serve per installare aggiornamenti sopra la versione esistente).
