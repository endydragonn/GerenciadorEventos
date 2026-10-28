#!/bin/sh
# Teto de linhas por arquivo .dart em lib/ (issue #84). Roda na raiz de Projeto/frontend.
# Arquivos acima do teto estão na baseline abaixo, com o tamanho atual como limite: podem
# encolher (e a linha sai daqui), mas não crescer. Telas novas/refatoradas devem caber no teto.
TETO=500

limite() {
  case "$1" in
    lib/telas/telaEdicaoPerfil.dart) echo 841 ;;
    lib/telas/telaVerificacaoEmail.dart) echo 840 ;;
    lib/telas/telaCadastro.dart) echo 670 ;;
    lib/telas/telaCriacaoEvento.dart) echo 665 ;;
    lib/telas/telaEdicaoEvento.dart) echo 523 ;;
    *) echo "$TETO" ;;
  esac
}

falhou=0
for f in $(find lib -name '*.dart'); do
  n=$(wc -l < "$f")
  max=$(limite "$f")
  if [ "$n" -gt "$max" ]; then
    echo "ACIMA DO TETO: $f tem $n linhas (limite $max)"
    falhou=1
  fi
done
[ "$falhou" -eq 0 ] && echo "Tamanho de arquivos OK (teto $TETO linhas)"
exit "$falhou"
