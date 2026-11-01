"""Funzioni comuni: adb, scoperta in rete, backup, registro delle modifiche, screenshot."""
import concurrent.futures as cf
import datetime as dt
import html
import ipaddress
import json
import os
import platform
import shutil
import socket
import subprocess
import sys
import time
import urllib.request

QUI = os.path.dirname(os.path.abspath(__file__))
BACKUP_ROOT = os.path.join(QUI, "backup")
LAUNCHER_PKG = "com.essenziale.tvlauncher"
LAUNCHER_ACT = LAUNCHER_PKG + "/.HomeActivity"


# ---------------------------------------------------------------- output

def info(msg):
    print(f"  {msg}", flush=True)


def titolo(msg):
    print(f"\n=== {msg} ===", flush=True)


def errore(msg):
    print(f"  [!] {msg}", file=sys.stderr, flush=True)


def chiedi(domanda, default=False):
    """Domanda sì/no. Ritorna default se l'input non è interattivo."""
    suff = "[S/n]" if default else "[s/N]"
    if not sys.stdin.isatty():
        info(f"{domanda} {suff} -> {'sì' if default else 'no'} (non interattivo)")
        return default
    while True:
        r = input(f"  ? {domanda} {suff} ").strip().lower()
        if not r:
            return default
        if r in ("s", "si", "sì", "y", "yes"):
            return True
        if r in ("n", "no"):
            return False


def carica_catalogo():
    with open(os.path.join(QUI, "catalogo.json"), encoding="utf-8") as f:
        return json.load(f)


# ---------------------------------------------------------------- adb

def trova_adb():
    exe = "adb.exe" if platform.system() == "Windows" else "adb"
    for cand in (os.path.join(QUI, "platform-tools", exe), shutil.which("adb")):
        if cand and os.path.isfile(cand):
            return cand
    errore("adb non trovato. Scarica le 'SDK Platform-Tools' da "
           "https://developer.android.com/tools/releases/platform-tools ed estrai la cartella "
           f"'platform-tools' dentro {QUI} (oppure aggiungi adb al PATH).")
    sys.exit(2)


class Adb:
    def __init__(self, serial=None):
        self.bin = trova_adb()
        self.serial = serial

    def _base(self):
        return [self.bin] + (["-s", self.serial] if self.serial else [])

    def run(self, *args, timeout=60, check=False, binary=False):
        p = subprocess.run(self._base() + list(args), capture_output=True, timeout=timeout)
        out = p.stdout if binary else p.stdout.decode("utf-8", "replace")
        if check and p.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} -> {p.stderr.decode('utf-8', 'replace').strip()}")
        return out if binary else out.replace("\r\n", "\n")

    def raw(self, *args, timeout=30):
        p = subprocess.run([self.bin] + list(args), capture_output=True, timeout=timeout)
        return (p.stdout + p.stderr).decode("utf-8", "replace").replace("\r\n", "\n")

    def sh(self, cmd, timeout=60):
        return self.run("shell", cmd, timeout=timeout).strip()

    def stato(self):
        return self.run("get-state", timeout=10).strip()

    def prop(self, k):
        return self.sh(f"getprop {k}")

    def sdk(self):
        try:
            return int(self.prop("ro.build.version.sdk"))
        except ValueError:
            return 0

    def pacchetti(self, flag=""):
        out = self.sh(f"pm list packages {flag}", timeout=120)
        return sorted({l.split(":", 1)[1].strip() for l in out.splitlines() if l.startswith("package:")})

    def screenshot(self, dest):
        data = self.run("exec-out", "screencap -p", timeout=30, binary=True)
        if not data.startswith(b"\x89PNG"):
            # vecchi adb/Windows: passa da file
            self.sh("screencap -p /sdcard/__shot.png")
            self.run("pull", "/sdcard/__shot.png", dest)
            self.sh("rm /sdcard/__shot.png")
        else:
            with open(dest, "wb") as f:
                f.write(data)
        return dest

    def attivita_in_primo_piano(self):
        out = self.sh("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity:'")
        for l in out.splitlines():
            for tok in l.split():
                if "/" in tok and "." in tok:
                    return tok.rstrip("}")
        out = self.sh("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
        for tok in out.split():
            if "/" in tok and "." in tok:
                return tok.rstrip("}")
        return ""

    def home_predefinita(self):
        out = self.sh("cmd package resolve-activity --brief -a android.intent.action.MAIN "
                      "-c android.intent.category.HOME")
        righe = [l.strip() for l in out.splitlines() if "/" in l]
        return righe[-1] if righe else ""


def dispositivi(adb):
    out = adb.raw("devices")
    res = []
    for l in out.splitlines()[1:]:
        parti = l.split()
        if len(parti) >= 2:
            res.append((parti[0], parti[1]))
    return res


# ---------------------------------------------------------------- scoperta in rete

def ip_locali():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for a in socket.gethostbyname_ex(socket.gethostname())[2]:
            if not a.startswith("127."):
                ips.add(a)
    except OSError:
        pass
    return sorted(ip for ip in ips if ipaddress.ip_address(ip).is_private)


def _porta_aperta(ip, porta, t=0.4):
    try:
        with socket.create_connection((ip, porta), timeout=t):
            return True
    except OSError:
        return False


def _nome_cast(ip):
    try:
        req = urllib.request.Request(f"http://{ip}:8008/setup/eureka_info?params=name,device_info",
                                     headers={"User-Agent": "tv-toolkit"})
        with urllib.request.urlopen(req, timeout=2) as r:
            j = json.loads(r.read().decode("utf-8", "replace"))
            nome = j.get("name", "")
            modello = (j.get("device_info") or {}).get("model_name", "")
            return " / ".join(x for x in (nome, modello) if x)
    except Exception:
        return ""


def scansiona_rete(subnet=None):
    """Cerca nella /24 locale: porta 5555 (ADB di rete) e 8008/8009 (Chromecast integrato = probabile TV)."""
    reti = [ipaddress.ip_network(subnet, strict=False)] if subnet else \
        [ipaddress.ip_network(f"{ip}/24", strict=False) for ip in ip_locali()]
    if not reti:
        errore("Impossibile determinare la rete locale: usa --subnet 192.168.1.0/24")
        return []
    trovati = {}

    def prova(ip):
        ip = str(ip)
        adb = _porta_aperta(ip, 5555)
        cast = _porta_aperta(ip, 8008) or _porta_aperta(ip, 8009)
        if adb or cast:
            return ip, adb, cast
        return None

    for rete in reti:
        info(f"Scansione di {rete} (porte 5555 ADB, 8008/8009 Cast)…")
        with cf.ThreadPoolExecutor(max_workers=128) as ex:
            for r in ex.map(prova, rete.hosts()):
                if r:
                    ip, adb, cast = r
                    trovati[ip] = {"ip": ip, "adb": adb, "cast": cast, "nome": _nome_cast(ip) if cast else ""}
    # Wireless debugging (Android 11+): annunci mDNS
    try:
        out = Adb().raw("mdns", "services", timeout=8)
        for l in out.splitlines():
            if "_adb-tls-connect" in l or "_adb._tcp" in l:
                for tok in l.split():
                    if ":" in tok and tok.split(":")[0].count(".") == 3:
                        ip, porta = tok.rsplit(":", 1)
                        d = trovati.setdefault(ip, {"ip": ip, "adb": False, "cast": False, "nome": ""})
                        d["mdns"] = f"{ip}:{porta}"
    except Exception:
        pass
    return sorted(trovati.values(), key=lambda d: tuple(int(x) for x in d["ip"].split(".")))


def connetti(indirizzo, attesa=90):
    if ":" not in indirizzo:
        indirizzo += ":5555"
    adb = Adb()
    info(adb.raw("connect", indirizzo).strip())
    t0 = time.time()
    avvisato = False
    while time.time() - t0 < attesa:
        stato = dict(dispositivi(adb)).get(indirizzo, "")
        if stato == "device":
            info(f"Connesso a {indirizzo}")
            return Adb(indirizzo)
        if stato in ("unauthorized", "") and not avvisato:
            info("Sulla TV dovrebbe comparire 'Consentire debug USB?': scegli 'Consenti sempre da questo computer'.")
            avvisato = True
        if stato == "offline" or stato == "":
            adb.raw("connect", indirizzo)
        time.sleep(2)
    errore(f"Nessuna autorizzazione da {indirizzo} entro {attesa}s.")
    return None


# ---------------------------------------------------------------- sessione: backup + registro + screenshot

class Sessione:
    """Una cartella di backup con stato iniziale, registro delle modifiche e screenshot di verifica."""

    def __init__(self, adb, cartella):
        self.adb = adb
        self.dir = cartella
        self.shots = os.path.join(cartella, "screenshot")
        os.makedirs(self.shots, exist_ok=True)
        self.registro = os.path.join(cartella, "registro.jsonl")
        self.rapporto = os.path.join(cartella, "rapporto.html")

    # -- creazione / riapertura
    @staticmethod
    def nuova(adb):
        modello = adb.prop("ro.product.model") or "tv"
        nome = "".join(c if c.isalnum() else "_" for c in modello)
        cartella = os.path.join(BACKUP_ROOT, f"{dt.datetime.now():%Y%m%d-%H%M%S}_{nome}")
        s = Sessione(adb, cartella)
        s.backup()
        return s

    @staticmethod
    def ultima(adb=None):
        if not os.path.isdir(BACKUP_ROOT):
            return None
        dirs = sorted(d for d in os.listdir(BACKUP_ROOT) if os.path.isfile(os.path.join(BACKUP_ROOT, d, "stato.json")))
        if not dirs:
            return None
        cartella = os.path.join(BACKUP_ROOT, dirs[-1])
        if adb is None:
            with open(os.path.join(cartella, "stato.json"), encoding="utf-8") as f:
                adb = Adb(json.load(f).get("serial"))
        return Sessione(adb, cartella)

    def stato(self):
        with open(os.path.join(self.dir, "stato.json"), encoding="utf-8") as f:
            return json.load(f)

    # -- backup
    def _salva(self, nome, testo):
        with open(os.path.join(self.dir, nome), "w", encoding="utf-8") as f:
            f.write(testo)

    def backup(self):
        titolo("Backup dello stato attuale")
        a = self.adb
        os.makedirs(self.dir, exist_ok=True)
        self._salva("getprop.txt", a.sh("getprop"))
        self._salva("pacchetti_tutti.txt", a.sh("pm list packages -f -u -U", timeout=120))
        abil = a.pacchetti("-e")
        disab = a.pacchetti("-d")
        terze = a.pacchetti("-3")
        self._salva("pacchetti_abilitati.txt", "\n".join(abil))
        self._salva("pacchetti_disabilitati.txt", "\n".join(disab))
        self._salva("pacchetti_terze_parti.txt", "\n".join(terze))
        impost = {}
        for ns in ("global", "secure", "system"):
            testo = a.sh(f"settings list {ns}", timeout=60)
            self._salva(f"impostazioni_{ns}.txt", testo)
            impost[ns] = dict(l.split("=", 1) for l in testo.splitlines() if "=" in l)
        home = a.home_predefinita()
        self._salva("home_predefinita.txt", home)
        self._salva("dumpsys_package_preferiti.txt", a.sh("dumpsys package preferred-activities", timeout=60))
        stato = {
            "serial": a.serial,
            "data": dt.datetime.now().isoformat(timespec="seconds"),
            "modello": a.prop("ro.product.model"),
            "produttore": a.prop("ro.product.manufacturer"),
            "android": a.prop("ro.build.version.release"),
            "sdk": a.sdk(),
            "build": a.prop("ro.build.fingerprint"),
            "home_predefinita": home,
            "abilitati": abil,
            "disabilitati": disab,
            "impostazioni": impost,
        }
        with open(os.path.join(self.dir, "stato.json"), "w", encoding="utf-8") as f:
            json.dump(stato, f, ensure_ascii=False, indent=1)
        info(f"{stato['produttore']} {stato['modello']} — Android {stato['android']} (SDK {stato['sdk']})")
        info(f"{len(abil)} pacchetti abilitati, {len(disab)} già disabilitati, {len(terze)} di terze parti")
        info(f"Backup salvato in: {self.dir}")
        self.screenshot("prima_di_tutto", "Stato iniziale (prima di qualsiasi modifica)")
        self._scrivi_script_ripristino()

    # -- registro azioni (ognuna con il suo comando inverso)
    def azioni(self):
        if not os.path.isfile(self.registro):
            return []
        with open(self.registro, encoding="utf-8") as f:
            return [json.loads(l) for l in f if l.strip()]

    def registra(self, tipo, descr, fatto, annulla, esito="ok", screenshot=None):
        voce = {"ora": dt.datetime.now().isoformat(timespec="seconds"), "tipo": tipo, "descr": descr,
                "comando": fatto, "annulla": annulla, "esito": esito, "screenshot": screenshot}
        with open(self.registro, "a", encoding="utf-8") as f:
            f.write(json.dumps(voce, ensure_ascii=False) + "\n")
        self._scrivi_script_ripristino()
        self._scrivi_rapporto()

    # -- screenshot di verifica
    def screenshot(self, nome, didascalia):
        n = len([f for f in os.listdir(self.shots) if f.endswith(".png")])
        dest = os.path.join(self.shots, f"{n:02d}_{nome}.png")
        try:
            self.adb.screenshot(dest)
            info(f"Screenshot: {dest}")
        except Exception as e:
            errore(f"Screenshot non riuscito: {e}")
            return None
        with open(os.path.join(self.shots, "didascalie.jsonl"), "a", encoding="utf-8") as f:
            f.write(json.dumps({"file": os.path.basename(dest), "testo": didascalia}, ensure_ascii=False) + "\n")
        self._scrivi_rapporto()
        return os.path.basename(dest)

    # -- script di ripristino autonomi (solo comandi adb, nessun Python richiesto)
    def _scrivi_script_ripristino(self):
        st = self.stato() if os.path.isfile(os.path.join(self.dir, "stato.json")) else {}
        serial = st.get("serial") or ""
        cmds = []
        for a in reversed(self.azioni()):
            if a.get("esito") == "ok" and a.get("annulla"):
                cmds.append((a["descr"], a["annulla"]))
        if st.get("home_predefinita"):
            cmds.append(("Ripristina la home originale",
                         f"cmd package set-home-activity {st['home_predefinita']}"))
        sh = ["#!/bin/sh", "# Ripristino generato automaticamente: annulla tutte le modifiche registrate.",
              f"# Backup: {self.dir}", f'ADB="${{ADB:-adb}}"', f'SERIAL="${{1:-{serial}}}"',
              '[ -n "$SERIAL" ] && case "$SERIAL" in *:*) "$ADB" connect "$SERIAL" >/dev/null;; esac',
              'A() { if [ -n "$SERIAL" ]; then "$ADB" -s "$SERIAL" shell "$1"; else "$ADB" shell "$1"; fi; }']
        bat = ["@echo off", "rem Ripristino generato automaticamente: annulla tutte le modifiche registrate.",
               f"rem Backup: {self.dir}", "set ADB=adb", f"set SERIAL={serial}",
               'if not "%~1"=="" set SERIAL=%~1', "%ADB% connect %SERIAL% >nul 2>&1"]
        for descr, c in cmds:
            sh.append(f"echo 'Annulla: {descr.replace(chr(39), '')}'; A '{c}'")
            bat.append(f"echo Annulla: {descr.replace('&', 'e')}")
            bat.append(f'%ADB% -s %SERIAL% shell "{c}"')
        sh.append("echo 'Ripristino completato. Premi HOME sul telecomando.'")
        bat.append("echo Ripristino completato. Premi HOME sul telecomando.")
        bat.append("pause")
        with open(os.path.join(self.dir, "ripristino.sh"), "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(sh) + "\n")
        os.chmod(os.path.join(self.dir, "ripristino.sh"), 0o755)
        with open(os.path.join(self.dir, "ripristino.bat"), "w", encoding="utf-8", newline="\r\n") as f:
            f.write("\n".join(bat) + "\n")

    def _scrivi_rapporto(self):
        didas = []
        p = os.path.join(self.shots, "didascalie.jsonl")
        if os.path.isfile(p):
            with open(p, encoding="utf-8") as f:
                didas = [json.loads(l) for l in f if l.strip()]
        righe = "".join(
            f"<tr><td>{html.escape(a['ora'][11:])}</td><td>{html.escape(a['descr'])}</td>"
            f"<td><code>{html.escape(a['comando'])}</code></td><td>{html.escape(a['esito'])}</td></tr>"
            for a in self.azioni())
        shots = "".join(
            f"<figure><img src='screenshot/{html.escape(d['file'])}' loading='lazy'>"
            f"<figcaption>{html.escape(d['file'])} — {html.escape(d['testo'])}</figcaption></figure>"
            for d in didas)
        doc = f"""<!doctype html><meta charset="utf-8"><title>Rapporto TV</title>
<style>body{{font-family:system-ui,sans-serif;margin:24px;background:#111;color:#eee}}
table{{border-collapse:collapse;width:100%}}td,th{{border-bottom:1px solid #333;padding:6px;text-align:left;font-size:14px}}
code{{color:#9cf}}figure{{margin:16px 0}}img{{max-width:100%;border:1px solid #333;border-radius:8px}}</style>
<h1>Rapporto modifiche Android TV</h1><p>Backup: {html.escape(self.dir)}</p>
<h2>Azioni</h2><table><tr><th>Ora</th><th>Descrizione</th><th>Comando</th><th>Esito</th></tr>{righe}</table>
<h2>Screenshot di verifica</h2>{shots}"""
        with open(self.rapporto, "w", encoding="utf-8") as f:
            f.write(doc)
