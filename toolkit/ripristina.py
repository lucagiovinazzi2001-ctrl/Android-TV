#!/usr/bin/env python3
"""
Ripristina la TV allo stato del backup: annulla in ordine inverso ogni modifica registrata,
riabilita qualsiasi pacchetto che era attivo al momento del backup, ripristina la home originale.

  python3 ripristina.py                     # usa l'ultimo backup
  python3 ripristina.py backup/<cartella>   # backup specifico
  python3 ripristina.py --tieni-launcher    # non rimuovere Essenziale
  python3 ripristina.py --serial 192.168.1.50:5555

Alternativa senza Python: backup/<cartella>/ripristino.sh (Mac/Linux) o ripristino.bat (Windows).
"""
import argparse
import os
import sys
import time

import tvlib as T
from tvlib import info, titolo, errore, chiedi


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cartella", nargs="?")
    ap.add_argument("--serial")
    ap.add_argument("--tieni-launcher", action="store_true")
    ap.add_argument("--si", action="store_true", help="non chiedere conferme")
    args = ap.parse_args()

    if args.cartella:
        s = T.Sessione(None, os.path.abspath(args.cartella))
    else:
        s = T.Sessione.ultima()
        if not s:
            errore(f"Nessun backup trovato in {T.BACKUP_ROOT}")
            sys.exit(1)
    st = s.stato()
    serial = args.serial or st.get("serial")
    adb = T.Adb(serial)
    if adb.stato() != "device":
        adb = T.connetti(serial) if serial and ":" in serial else None
        if not adb:
            errore("TV non raggiungibile: accendila, verifica il Wi-Fi e il debug ADB.")
            sys.exit(1)
    s.adb = adb

    titolo(f"Ripristino da {s.dir}")
    info(f"Backup del {st['data']} — {st['produttore']} {st['modello']}")
    if not args.si and not chiedi("Procedo con il ripristino?", True):
        return
    s.screenshot("prima_del_ripristino", "Stato prima del ripristino")

    titolo("1/3 Annullo le modifiche registrate (in ordine inverso)")
    for a in reversed(s.azioni()):
        cmd = a.get("annulla")
        if a.get("esito") != "ok" or not cmd:
            continue
        if cmd.startswith(f"pm uninstall {T.LAUNCHER_PKG}"):
            continue  # gestito alla fine
        out = adb.sh(cmd)
        info(f"↺ annullo «{a['descr']}»  {out[:80]}")

    titolo("2/3 Verifica pacchetti rispetto al backup")
    ora_disab = set(adb.pacchetti("-d"))
    da_riab = sorted(set(st["abilitati"]) & ora_disab)
    for pkg in da_riab:
        info(f"↺ riabilito {pkg}: {adb.sh(f'pm enable --user 0 {pkg}')[:60]}")
    if not da_riab:
        info("Tutti i pacchetti attivi al momento del backup sono di nuovo attivi.")
    for ns, valori in st.get("impostazioni", {}).items():
        for chiave in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            if ns == "global":
                ora = adb.sh(f"settings get global {chiave}")
                prima = valori.get(chiave)
                if prima is None and ora not in ("null", ""):
                    adb.sh(f"settings delete global {chiave}")
                elif prima is not None and ora != prima:
                    adb.sh(f"settings put global {chiave} {prima}")

    titolo("3/3 Schermata iniziale")
    home = st.get("home_predefinita")
    if home:
        info(f"Home originale: {home} -> {adb.sh(f'cmd package set-home-activity {home}')}")
    installato = T.LAUNCHER_PKG in adb.pacchetti()
    if installato and not args.tieni_launcher and (args.si or chiedi("Rimuovo anche la home Essenziale?", True)):
        info(adb.sh(f"pm uninstall {T.LAUNCHER_PKG}"))
    adb.sh("input keyevent KEYCODE_HOME")
    time.sleep(3)
    fg = adb.attivita_in_primo_piano()
    info(f"In primo piano dopo HOME: {fg}")
    s.screenshot("dopo_ripristino", f"Dopo il ripristino, HOME -> {fg}")
    s.registra("ripristino", "Ripristino completo eseguito", "ripristina.py", None)
    info(f"Fatto. Rapporto: {s.rapporto}")


if __name__ == "__main__":
    main()
