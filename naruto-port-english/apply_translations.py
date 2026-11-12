#!/usr/bin/env python3
"""Copy decoded/ to translated/ and replace the launcher's Portuguese string literals with English.

Reports table entries that were not found, translated literals that look like they are used as
keys or compared (so they can be checked by hand), and Portuguese-looking literals still left.
"""
import collections
import re
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from smali_strings import CONST_STRING, escape, literals, smali_files, unescape  # noqa: E402
from translations import EN  # noqa: E402

SRC, DST = Path("decoded"), Path("translated")
RISKY_CALL = re.compile(r"->(equals|equalsIgnoreCase|contains|startsWith|endsWith|indexOf|hashCode|getString|putString|"
                        r"getInt|putInt|getBoolean|putBoolean|valueOf|parse\w*)\(|Ljava/io/File;-><init>|putExtra")
STILL_PT = re.compile(r"[\u00c0-\u00ff]|\b(n\u00e3o|toque|jogo|tela|bot\u00e3o|bot\u00f5es|controle|arquivo|pasta|pacote|aparelho|"
                      r"celular|quadros|imagem|bordas|vezes|dentro|leve|fino|desligad|ligad|baixa|alta|abrir|fechar|"
                      r"cancelar|concluir|depois|manter|mostrar|ocultar|trocar|importar|jogar|mapear|voltar|atualizar|"
                      r"esticar|qualidade|desempenho|nitidez|apoio|ataque|defesa|despertar|ferramenta|pulo|sistema|"
                      r"selecionado|tamanho|pausa|suporte|itens|preparando|pular|baixando|aten\u00e7\u00e3o|aviso|seu|sua|"
                      r"de|em|para|com|sem|ou|erro|corrida|atalho|renderizador|escala)\b", re.I)


def main():
    if DST.exists():
        shutil.rmtree(DST)
    shutil.copytree(SRC, DST)

    hits = collections.Counter()
    risky = []
    for path in smali_files(DST / "smali"):
        lines = path.read_text(encoding="utf-8").split("\n")
        changed = False
        for i, line in enumerate(lines):
            m = CONST_STRING.match(line)
            if not m:
                continue
            text = unescape(m.group(2))
            if text not in EN:
                continue
            lines[i] = f'{m.group(1)}"{escape(EN[text])}"'
            hits[text] += 1
            changed = True
            reg = m.group(1).split()[-1].rstrip(",")
            for follow in lines[i + 1:i + 9]:
                if re.search(r"[{ ,]" + re.escape(reg) + r"[,}]", follow) and RISKY_CALL.search(follow):
                    risky.append(f"{path.relative_to(DST)}:{i + 1} {text!r}: {follow.strip()}")
                    break
        if changed:
            path.write_text("\n".join(lines), encoding="utf-8")

    missing = [k for k in EN if k not in hits]
    print(f"replaced {sum(hits.values())} literals ({len(hits)} distinct of {len(EN)} table entries)")
    for k in missing:
        print("NOT FOUND:", repr(k))
    for r in risky:
        print("CHECK:", r)
    left = sorted({t for _, _, _, t in literals(DST / "smali") if STILL_PT.search(t)})
    for t in left:
        print("LEFT:", repr(t))


if __name__ == "__main__":
    main()
