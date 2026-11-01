#!/usr/bin/env python3
"""
Toolkit Android TV: trova la TV in rete, backup, debloat reversibile (solo DISABILITAZIONE,
mai disinstallazione), installazione della home "Essenziale", screenshot di verifica.

Uso rapido:   python3 tv.py tutto
Comandi:      trova | connetti IP | abbina IP:PORTA CODICE | backup | piano | debloat |
              launcher | screenshot NOME | stato
Ripristino:   python3 ripristina.py         (oppure backup/<cartella>/ripristino.sh|.bat)
"""
import argparse
import json
import os
import sys
import time

import tvlib as T
from tvlib import info, titolo, errore, chiedi


# ---------------------------------------------------------------- selezione dispositivo

def scegli_dispositivo(args):
    if args.serial:
        a = T.Adb(args.serial)
        if ":" in args.serial and a.stato() != "device":
            a = T.connetti(args.serial) or sys.exit(1)
        return a
    pronti = [s for s, st in T.dispositivi(T.Adb()) if st == "device"]
    if len(pronti) == 1:
        return T.Adb(pronti[0])
    if len(pronti) > 1:
        errore("Più dispositivi collegati: specifica --serial " + " | ".join(pronti))
        sys.exit(1)
    s = T.Sessione.ultima()
    if s and s.stato().get("serial") and ":" in s.stato()["serial"]:
        info(f"Riconnessione all'ultima TV usata: {s.stato()['serial']}")
        a = T.connetti(s.stato()["serial"], attesa=30)
        if a:
            return a
    errore("Nessuna TV connessa. Esegui prima:  python3 tv.py trova   e poi   python3 tv.py connetti <IP>")
    sys.exit(1)


def sessione(adb, args):
    """Riusa l'ultimo backup della stessa TV, altrimenti ne crea uno nuovo (sempre PRIMA di modificare)."""
    if not getattr(args, "nuovo_backup", False):
        s = T.Sessione.ultima(adb)
        if s and s.stato().get("serial") == adb.serial:
            info(f"Uso il backup esistente: {s.dir}")
            return s
    return T.Sessione.nuova(adb)


def torna_home(adb):
    adb.sh("input keyevent KEYCODE_HOME")
    time.sleep(2.5)


def tv_in_salute(adb):
    try:
        if adb.stato() != "device":
            return False
        return bool(adb.sh("pidof com.android.systemui") or adb.sh("ps -A | grep -c systemui").strip() not in ("", "0"))
    except Exception:
        return False


# ---------------------------------------------------------------- comandi

def cmd_trova(args):
    titolo("Ricerca della TV sulla rete")
    trovati = T.scansiona_rete(args.subnet)
    if not trovati:
        info("Nessun dispositivo trovato. Verifica che PC e TV siano sulla stessa rete Wi-Fi.")
        return []
    for d in trovati:
        etichetta = []
        if d.get("nome"):
            etichetta.append(d["nome"])
        if d["adb"]:
            etichetta.append("ADB aperto (5555)")
        if d.get("mdns"):
            etichetta.append(f"Debug wireless {d['mdns']}")
        if d["cast"] and not d["adb"] and not d.get("mdns"):
            etichetta.append("TV/Chromecast ma ADB non attivo")
        info(f"{d['ip']:<16} {' · '.join(etichetta)}")
    if not any(d["adb"] or d.get("mdns") for d in trovati):
        print("""
  Nessuna TV con ADB attivo. Sulla TV:
   1. Impostazioni > Sistema > Info > tocca 7 volte 'Build del sistema operativo Android TV'
      (sui Google TV: Impostazioni > Sistema > Informazioni > Build).
   2. Impostazioni > Sistema > Opzioni sviluppatore > attiva 'Debug USB'
      e, se presente, 'Debug di rete/ADB tramite rete' oppure 'Debug wireless'.
   3. Rilancia:  python3 tv.py trova
   Con 'Debug wireless' (Android 11+): Associa dispositivo con codice ->
      python3 tv.py abbina IP:PORTA_ABBINAMENTO CODICE   poi   python3 tv.py connetti IP:PORTA""")
    return trovati


def cmd_connetti(args):
    a = T.connetti(args.indirizzo)
    if not a:
        sys.exit(1)
    info(f"{a.prop('ro.product.manufacturer')} {a.prop('ro.product.model')} — Android {a.prop('ro.build.version.release')}")
    return a


def cmd_abbina(args):
    info(T.Adb().raw("pair", args.indirizzo, args.codice, timeout=30).strip())


def cmd_backup(args):
    adb = scegli_dispositivo(args)
    T.Sessione.nuova(adb)


def costruisci_piano(adb):
    cat = T.carica_catalogo()
    mai = set(cat["mai_toccare"])
    abil = set(adb.pacchetti("-e"))
    noti = {p["pkg"] for p in cat["pacchetti"]} | {l["pkg"] for l in cat["launcher_di_sistema"]}
    piano = {"sicuro": [], "consigliato": [], "facoltativo": [], "da_valutare": [], "impostazioni": []}
    for p in cat["pacchetti"]:
        if p["pkg"] in abil and p["pkg"] not in mai:
            piano[p["livello"]].append(p)
    for pkg in sorted(abil - noti - mai):
        low = pkg.lower()
        if any(w in low for w in cat["parole_sospette"]) and not any(w in low for w in cat["parole_vietate"]):
            piano["da_valutare"].append({"pkg": pkg, "nome": pkg, "cat": "sconosciuto", "livello": "facoltativo",
                                         "note": "Nome sospetto (pubblicità/telemetria?), non presente nel catalogo"})
    for imp in cat["impostazioni"]:
        piano["impostazioni"].append(imp)
    piano["launcher_sistema"] = [l for l in cat["launcher_di_sistema"] if l["pkg"] in abil]
    return piano


def cmd_piano(args):
    adb = scegli_dispositivo(args)
    piano = costruisci_piano(adb)
    if args.json:
        print(json.dumps(piano, ensure_ascii=False, indent=1))
        return
    nomi = {"sicuro": "Pubblicità/telemetria (sicuri)", "consigliato": "Consigliati",
            "facoltativo": "App che potresti usare", "da_valutare": "Sospetti fuori catalogo"}
    for k, t in nomi.items():
        titolo(f"{t}: {len(piano[k])}")
        for p in piano[k]:
            info(f"{p['pkg']:<55} {p['nome']}" + (f"  — {p['note']}" if p.get("note") else ""))
    titolo("Home di sistema attive")
    for l in piano["launcher_sistema"]:
        info(f"{l['pkg']:<55} {l['nome']}")


def disabilita(s, p):
    adb = s.adb
    out = adb.sh(f"pm disable-user --user 0 {p['pkg']}")
    ok = "disabled" in out.lower() and "error" not in out.lower() and "exception" not in out.lower()
    s.registra("disabilita", f"Disabilita {p['nome']} ({p['pkg']})", f"pm disable-user --user 0 {p['pkg']}",
               f"pm enable --user 0 {p['pkg']}" if ok else None, "ok" if ok else f"errore: {out[:160]}")
    info(("  ✓ " if ok else "  ✗ ") + f"{p['pkg']}" + ("" if ok else f"  ({out.strip()[:120]})"))
    return ok


def applica_gruppo(s, nome_gruppo, pacchetti):
    if not pacchetti:
        return
    fatti = [p for p in pacchetti if disabilita(s, p)]
    torna_home(s.adb)
    if not tv_in_salute(s.adb):
        errore("La TV non risponde come previsto: annullo subito questo gruppo.")
        for p in fatti:
            s.adb.sh(f"pm enable --user 0 {p['pkg']}")
            s.registra("ripristina", f"Annullamento automatico {p['pkg']}", f"pm enable --user 0 {p['pkg']}", None)
    s.screenshot(f"dopo_{nome_gruppo}", f"Schermata dopo aver disabilitato: {', '.join(p['pkg'] for p in fatti) or 'nulla'}")


def imposta(s, imp):
    a = s.adb
    for ns, chiave, val in imp["valori"]:
        vecchio = a.sh(f"settings get {ns} {chiave}")
        a.sh(f"settings put {ns} {chiave} {val}")
        annulla = f"settings delete {ns} {chiave}" if vecchio in ("", "null") else f"settings put {ns} {chiave} {vecchio}"
        s.registra("impostazione", f"{imp['nome']}: {ns}/{chiave} {vecchio} -> {val}",
                   f"settings put {ns} {chiave} {val}", annulla)
    info(f"  ✓ {imp['nome']}")


def cmd_debloat(args):
    adb = scegli_dispositivo(args)
    s = sessione(adb, args)
    piano = costruisci_piano(adb)
    solo = set(args.solo.split(",")) if args.solo else None
    escludi = set(args.escludi.split(",")) if args.escludi else set()

    def filtra(lista):
        return [p for p in lista if p["pkg"] not in escludi and (solo is None or p["pkg"] in solo)]

    if solo is not None:
        # modalità non interattiva: disabilita esattamente i pacchetti indicati (se presenti e abilitati)
        tutti = [p for k in ("sicuro", "consigliato", "facoltativo", "da_valutare") for p in piano[k]]
        tutti += [dict(l, cat="launcher", livello="facoltativo") for l in piano["launcher_sistema"]]
        scelti = filtra(tutti)
        sconosciuti = solo - {p["pkg"] for p in scelti}
        if sconosciuti:
            errore(f"Ignorati (non installati, già disabilitati o protetti): {', '.join(sorted(sconosciuti))}")
        titolo(f"Disabilito {len(scelti)} pacchetti indicati")
        applica_gruppo(s, "selezione", scelti)
        for imp in piano["impostazioni"]:
            if imp["id"] in (args.impostazioni or "").split(","):
                imposta(s, imp)
        s.screenshot("fine_debloat", "Fine debloat")
        info(f"Rapporto: {s.rapporto}")
        return

    if not sys.stdin.isatty():
        errore("Modalità non interattiva: per sicurezza non disabilito nulla senza conferma.")
        errore("Indica esattamente cosa disabilitare con --solo pkg1,pkg2 (vedi: python3 tv.py piano).")
        sys.exit(1)

    titolo("1/4 Pubblicità e telemetria")
    sicuri = filtra(piano["sicuro"])
    for p in sicuri:
        info(f"- {p['nome']}  ({p['pkg']})")
    if sicuri:
        if chiedi(f"Disabilito questi pacchetti ({len(sicuri)})?", True):
            applica_gruppo(s, "pubblicita_telemetria", sicuri)
        else:
            applica_gruppo(s, "pubblicita_telemetria", [p for p in sicuri if chiedi(f"  {p['nome']}?", False)])
    else:
        info("Niente da fare.")

    titolo("2/4 Contenuti promossi e servizi inutili")
    scelti = [p for p in filtra(piano["consigliato"])
              if chiedi(f"Disabilito «{p['nome']}» ({p['pkg']})" + (f" — {p['note']}" if p.get("note") else "") + "?", True)]
    applica_gruppo(s, "contenuti", scelti)

    titolo("3/4 App e funzioni che potresti usare (default: NO)")
    scelti = [p for p in filtra(piano["facoltativo"] + piano["da_valutare"])
              if chiedi(f"Disabilito «{p['nome']}» ({p['pkg']})" + (f" — {p['note']}" if p.get("note") else "") + "?", False)]
    applica_gruppo(s, "app_facoltative", scelti)

    titolo("4/4 Velocità")
    for imp in piano["impostazioni"]:
        if chiedi(f"{imp['nome']}?", imp["livello"] == "consigliato"):
            imposta(s, imp)
    if chiedi("Svuoto le cache delle app (non reversibile ma innocuo: si ricreano da sole)?", False):
        adb.sh("pm trim-caches 999G", timeout=180)
        s.registra("cache", "Svuotate le cache delle app", "pm trim-caches 999G", None)
    torna_home(adb)
    s.screenshot("fine_debloat", "Home al termine del debloat")
    info(f"Fatto. Rapporto con screenshot: {s.rapporto}")
    info(f"Ripristino: python3 ripristina.py   oppure   {os.path.join(s.dir, 'ripristino.sh')}")


def app_avviabili(adb):
    pk = set()
    for cat in ("android.intent.category.LEANBACK_LAUNCHER", "android.intent.category.LAUNCHER"):
        out = adb.sh(f"cmd package query-activities --brief -a android.intent.action.MAIN -c {cat}")
        for l in out.splitlines():
            l = l.strip()
            if "/" in l and " " not in l:
                pk.add(l.split("/")[0])
    if not pk:
        pk = set(adb.pacchetti("-3"))
    pk.discard(T.LAUNCHER_PKG)
    return sorted(pk)


def scegli_preferite(adb, args):
    if args.preferite:
        return [p.strip() for p in args.preferite.split(",") if p.strip()]
    nomi = T.carica_catalogo()["app_note"]
    apps = sorted(app_avviabili(adb), key=lambda p: (p not in nomi, nomi.get(p, p).lower()))
    titolo("App installate")
    for i, p in enumerate(apps, 1):
        info(f"{i:>3}. {nomi.get(p, '')[:22]:<22} {p}")
    if not sys.stdin.isatty():
        return [p for p in apps if p in nomi][:8]
    r = input("  ? Numeri delle app preferite nell'ordine voluto (es. 3,1,7) [INVIO = automatico]: ").strip()
    if not r:
        return [p for p in apps if p in nomi][:8]
    out = []
    for n in r.replace(" ", "").split(","):
        if n.isdigit() and 1 <= int(n) <= len(apps) and apps[int(n) - 1] not in out:
            out.append(apps[int(n) - 1])
    return out


def cmd_launcher(args):
    adb = scegli_dispositivo(args)
    s = sessione(adb, args)
    stato = s.stato()
    apk = args.apk or os.path.join(T.QUI, "Essenziale.apk")
    dati = f"/sdcard/Android/data/{T.LAUNCHER_PKG}/files"

    titolo("1/5 Installazione di Essenziale")
    gia = T.LAUNCHER_PKG in adb.pacchetti()
    out = adb.run("install", "-r", apk, timeout=180)
    if "Success" not in out:
        errore(f"Installazione fallita: {out.strip()}")
        sys.exit(1)
    if not gia:
        s.registra("installa", "Installa la home Essenziale", f"install {os.path.basename(apk)}",
                   f"pm uninstall {T.LAUNCHER_PKG}")
    info("✓ Installata")
    adb.sh(f"am start -W -n {T.LAUNCHER_ACT}", timeout=30)
    time.sleep(3)
    s.screenshot("essenziale_primo_avvio", "Essenziale appena avviata (prima della configurazione)")

    titolo("2/5 App preferite")
    fav = scegli_preferite(adb, args)
    loc = os.path.join(s.dir, "favorites.txt")
    with open(loc, "w", encoding="utf-8") as f:
        f.write("# Una app per riga, nell'ordine della barra\n" + "\n".join(fav) + "\n")
    adb.sh(f"mkdir -p {dati}/wallpapers")
    adb.run("push", loc, f"{dati}/favorites.txt")
    info("Preferite: " + ", ".join(fav))

    titolo("3/5 Sfondi e impostazioni")
    conf = [f"rotation_seconds={args.rotazione}", "fade_millis=1500",
            f"online_wallpapers={'false' if args.solo_sfondi_locali else 'true'}"]
    loc_conf = os.path.join(s.dir, "config.properties")
    with open(loc_conf, "w", encoding="utf-8") as f:
        f.write("\n".join(conf) + "\n")
    adb.run("push", loc_conf, f"{dati}/config.properties")
    if args.sfondi:
        imgs = [os.path.join(args.sfondi, f) for f in sorted(os.listdir(args.sfondi))
                if f.lower().endswith((".jpg", ".jpeg", ".png", ".webp"))]
        for img in imgs:
            adb.run("push", img, f"{dati}/wallpapers/", timeout=120)
        info(f"Caricati {len(imgs)} sfondi tuoi")
    else:
        info("Sfondi: foto naturalistiche in vetrina da Wikimedia Commons (scaricate dalla TV, con crediti)")
    adb.sh(f"am force-stop {T.LAUNCHER_PKG}")
    adb.sh(f"am start -W -n {T.LAUNCHER_ACT}", timeout=30)
    time.sleep(args.attesa_sfondo)  # tempo per scaricare il primo sfondo
    s.screenshot("essenziale_configurata", "Essenziale con preferite e primo sfondo")

    titolo("4/5 Imposto Essenziale come schermata iniziale")
    r = adb.sh(f"cmd package set-home-activity {T.LAUNCHER_ACT}")
    info(r or "(nessuna risposta)")
    if "Success" in r or r == "":
        s.registra("home", "Essenziale come home predefinita", f"cmd package set-home-activity {T.LAUNCHER_ACT}",
                   f"cmd package set-home-activity {stato['home_predefinita']}" if stato.get("home_predefinita") else None)
    torna_home(adb)
    fg = adb.attivita_in_primo_piano()
    info(f"Dopo HOME in primo piano: {fg}")
    s.screenshot("dopo_home", f"Pressione di HOME: in primo piano {fg}")

    titolo("5/5 Verifica")
    if T.LAUNCHER_PKG in fg:
        info("✓ Il tasto HOME apre Essenziale.")
    else:
        piano = costruisci_piano(adb)
        ls = piano["launcher_sistema"]
        info("La home di sistema si riprende il tasto HOME (comune su Google TV).")
        info("Soluzione reversibile: disabilitare la home di sistema (Essenziale è già installata e verificata).")
        da = [l for l in ls if args.disabilita_home_sistema or
              chiedi(f"Disabilito «{l['nome']}» ({l['pkg']})?", False)]
        if da:
            applica_gruppo(s, "home_sistema", [dict(l, cat="launcher") for l in da])
            torna_home(adb)
            fg = adb.attivita_in_primo_piano()
            info(f"Dopo HOME in primo piano: {fg}")
            s.screenshot("verifica_home", f"Seconda verifica HOME: {fg}")
            if T.LAUNCHER_PKG not in fg:
                info("Se compare la scelta dell'app home, seleziona «Essenziale» e «Sempre».")
    info(f"Rapporto con screenshot: {s.rapporto}")


def cmd_screenshot(args):
    adb = scegli_dispositivo(args)
    s = sessione(adb, args)
    s.screenshot(args.nome, args.nome)


def cmd_stato(args):
    adb = scegli_dispositivo(args)
    info(f"Dispositivo: {adb.serial}")
    info(f"Home predefinita: {adb.home_predefinita()}")
    info(f"In primo piano: {adb.attivita_in_primo_piano()}")
    s = T.Sessione.ultima(adb)
    if s:
        az = s.azioni()
        info(f"Ultimo backup: {s.dir} — {len(az)} azioni registrate")


def cmd_tutto(args):
    if not args.serial and not [d for d, st in T.dispositivi(T.Adb()) if st == "device"]:
        trovati = [d for d in cmd_trova(args) if d["adb"] or d.get("mdns")]
        if not trovati:
            sys.exit(1)
        if len(trovati) == 1:
            ip = trovati[0].get("mdns") or trovati[0]["ip"]
        else:
            for i, d in enumerate(trovati, 1):
                info(f"{i}. {d['ip']} {d.get('nome', '')}")
            ip = trovati[int(input("  ? Quale TV? ")) - 1]["ip"]
        a = T.connetti(ip) or sys.exit(1)
        args.serial = a.serial
    adb = scegli_dispositivo(args)
    T.Sessione.nuova(adb)
    args.nuovo_backup = False
    cmd_debloat(args)
    if chiedi("Installo ora la schermata iniziale Essenziale?", True):
        cmd_launcher(args)


def main():
    ap = argparse.ArgumentParser(description="Debloat reversibile + home Essenziale per Android TV")
    ap.add_argument("--serial", help="es. 192.168.1.50:5555")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("trova"); p.add_argument("--subnet"); p.set_defaults(f=cmd_trova)
    p = sub.add_parser("connetti"); p.add_argument("indirizzo"); p.set_defaults(f=cmd_connetti)
    p = sub.add_parser("abbina"); p.add_argument("indirizzo"); p.add_argument("codice"); p.set_defaults(f=cmd_abbina)
    p = sub.add_parser("backup"); p.set_defaults(f=cmd_backup)
    p = sub.add_parser("piano"); p.add_argument("--json", action="store_true"); p.set_defaults(f=cmd_piano)
    for nome, f in (("debloat", cmd_debloat), ("launcher", cmd_launcher), ("tutto", cmd_tutto)):
        p = sub.add_parser(nome)
        p.add_argument("--nuovo-backup", action="store_true")
        p.add_argument("--subnet")
        p.add_argument("--solo", help="debloat non interattivo: pacchetti da disabilitare, separati da virgola")
        p.add_argument("--escludi", help="pacchetti da non toccare, separati da virgola")
        p.add_argument("--impostazioni", help="id impostazioni da applicare con --solo (es. animazioni)")
        p.add_argument("--apk")
        p.add_argument("--preferite", help="package delle preferite in ordine, separati da virgola")
        p.add_argument("--sfondi", help="cartella con le tue foto (altrimenti Wikimedia Commons)")
        p.add_argument("--solo-sfondi-locali", action="store_true")
        p.add_argument("--rotazione", type=int, default=90, help="secondi tra uno sfondo e l'altro")
        p.add_argument("--disabilita-home-sistema", action="store_true")
        p.add_argument("--attesa-sfondo", type=int, default=12, help=argparse.SUPPRESS)
        p.set_defaults(f=f)
    p = sub.add_parser("screenshot"); p.add_argument("nome"); p.set_defaults(f=cmd_screenshot)
    p = sub.add_parser("stato"); p.set_defaults(f=cmd_stato)
    args = ap.parse_args()
    args.f(args)


if __name__ == "__main__":
    main()
