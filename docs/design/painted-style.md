# Estilo de render pintado

Implementado em 20/10/2026 na branch `feature/painted-style`. A opção substitui o antigo toggle
de contornos: `Configurações → Aparência → Render` alterna entre **Simples** (sem contornos),
**Contornos** (o visual anterior e padrão), **Pintado** e **Pintado sem contornos**. A preferência
é a mesma para minimapa, mapa mundial e tela do Mod Menu.

**Pintado sem contornos** (`MapStyle.ink` falso) é o mesmo acabamento sem nenhuma linha de
nanquim: nem nas arestas, nem entre cores, nem na costa. Ficam a tinta por face, a luz em dois
tons, a água em aguadas com seus anéis e a espuma, o brilho de borda e a textura.

## Objetivo

Fazer o terreno explorado parecer um mapa de fantasia pintado à mão: áreas de cor chapadas, luz
cel‑shaded, linhas de nanquim sutis e costas desenhadas, sem a textura e o sombreamento suave dos
blocos do Minecraft.

## Onde acontece

Tudo é calculado no bake de tiles (CPU, `TileBaker` + `PaintedFinish`), não em shader. Assim o
estilo funciona igual nos 21 alvos e loaders, entra no mesmo refresh atômico de aparência
(`MapStyle.looksLike/looksHash` incluem `painted`, então `appearance.bin` e a fila em segundo plano
redesenham os tiles salvos) e o compositor dia/noite continua só misturando os dois planos.

### Luz (`TileBaker`)

- **Dois tons**: cada superfície fica com a cor iluminada pelo sol ou com uma única cor de sombra
  projetada. Sem oclusão de cantos (AO), oclusão de céu ou degradê de altitude; paredes ficam só
  um pouco mais escuras que o chão (0,86).
- **Sombras nítidas**: um raio por pixel na grade de blocos (`docs/design/pixel-grid.md`), sem
  jitter do sol, então a sombra é uma mancha chapada recortada nos texels de cada face.
- **Paleta**: sol dourado, sombra lavanda‑azulada, um pouco mais de luz ambiente
  (`MapStyle.paint()`), e uma curva de pigmento em que nada chega ao preto puro nem estoura no
  branco (piso levemente violeta, ombro nos realces).
- **Plantas** sobre o chão pesam menos (0,22 em vez de 0,4), para o capim não salpicar o terreno.
- **Água em aguadas**: a profundidade vertical cai em quatro faixas (rasa < 1,25, < 3,5, < 8 e
  funda), cada uma mais escura e mostrando menos fundo; a cor do bioma é puxada 55% para um
  azul‑petróleo de mapa pintado.

### Acabamento (`PaintedFinish`)

Recebe o patch rastreado com 12 px de borda e devolve só o miolo:

1. **Tinta**: filtro de Kuwahara sobre as faces da grade (cada uma uma célula de 4×3 px), no
   albedo: o dab de uma face é a média da janela de 2×2 faces que menos varia, aplicado a 90%. Cada
   pixel muda pela razão entre o dab e o seu albedo, então a cor das faces derrete em manchas e a
   luz por texel (sombras) fica intacta; nada sai da grade. Dia e noite recebem o mesmo fator.
2. **Nanquim**: linha sépia quente nos pixels que ficam atrás de uma aresta (peso pela distância
   exata calculada pelo `TileBaker` a partir das faces), linha mais leve no lado escuro de faces
   vizinhas de cores bem diferentes, linha firme na terra ao longo de toda costa e anéis azulados
   onde a água muda de aguada. Só os 4 vizinhos: 1 px, sem engrossar nas quinas. O traço varia de
   peso com um ruído de mundo, como pena à mão; a linha mais forte vence em vez de somar. Nada de
   contorno do fundo através da água.
3. **Luz**: um brilho quente fino nas bordas ensolaradas voltadas para a câmera e uma linha de
   espuma na água junto à terra.
4. **Textura**: pinceladas curtas que seguem um campo de direção que gira devagar (longas e
   horizontais na água), as mesmas pinceladas 4× maiores na terra (para campos abertos mostrarem
   pincel também no zoom médio), uma aguada grande irregular com leve deriva quente/fria e grão de
   papel — tudo discreto, para não devolver a textura que o passo 1 tirou.

### Garantias

- Todos os padrões são indexados por pixel de mundo, nunca pela posição no patch: um patch parcial
  é idêntico aos mesmos pixels do tile inteiro e tiles vizinhos não têm emenda
  (`PaintedFinishTest.aPatchMatchesTheSamePixelsOfAWholeArea`).
- Alpha é preservado e pixels desconhecidos continuam desconhecidos: o estilo nunca aumenta a
  cobertura nem revela terreno (`keepsEveryAlphaSoCoverageNeverGrows`).
- O alcance extra (12 px, três blocos) fica dentro das margens já usadas por `TileInvalidation`
  (a sombra e a oclusão de céu já pedem bem mais), então edições redesenham tudo o que afetam.
- Trocar de estilo preserva exploração e tiles residentes (cenário `settings-refresh`), e os tiles
  da tela mudam juntos (cenário `style-swap`; ver "Troca de estilo").
- Simples e Contornos não usam nada do acabamento pintado; os três estilos compartilham a grade e o
  contorno geométrico.

## Nitidez

Os tiles são ampliados com NEAREST sempre que um texel cobre pelo menos um pixel real de tela
(zoom × escala da GUI × tamanho do nível de overview), e a vista desenhada encaixa num número
inteiro de pixels de tela por texel; ver `docs/design/pixel-grid.md`.

## Migração

Configs antigas só têm `outlines`: `true` vira Contornos, `false` vira Simples, e o campo deixa de
ser gravado. O padrão para configs novas continua Contornos.

## Custo

O acabamento é uma passada linear sobre a área: médias por face, um Kuwahara por face (não por
pixel), 4 vizinhos e consultas de tabela. Medido num tile inteiro de 512² com dia e noite (JUnit,
melhor de 8, nesta máquina): ~31 ms, contra ~0,2–0,34 s de bake médio por tile nos logs do teste de
cliente com a grade. As tabelas de pincel, aguada e grão são geradas uma vez na carga da classe
(256², 128², 128²).

## Troca de estilo

Como o estilo vem do bake e os tiles salvos guardam só RGB, trocar de estilo (ou de sombreamento
de profundidade) re-rastreia os tiles residentes. Antes o refresh de aparência esperava *todos* os
residentes e trocava tudo num único quadro; com dezenas ou centenas de tiles e ~0,2–0,3 s por tile
por baker, o clique no livro levava segundos para aparecer. Agora a troca é **viewport-first**
(`AppearanceRefresh.swappable`, `MapLevel.swapStaged`):

- A fila já começa pelos tiles que as vistas desenham. Eles trocam **juntos, no mesmo quadro**,
  assim que todos estiverem prontos: a tela nunca mostra metade num estilo e metade no outro.
- Os residentes que nenhuma vista desenha (inclusive o anel pré-carregado fora da borda,
  `MapLevel.OFF_SCREEN`) trocam conforme chegam, até 4 por quadro; ninguém os vê mudar.
- Overviews continuam atrás da barreira do writer e trocam juntos por último, depois de todos os
  tiles detalhados.
- Antes de cada troca, geração e frescor de terreno/luz são conferidos de novo; um resultado velho
  volta para a fila e segura o grupo da tela. Depois de trocado, o tile segue o caminho comum
  (edições sobem direto) e não é rastreado de novo pela revisão. `appearance.bin` é carimbado na
  troca, como antes no commit único.
- Bakes que ainda estavam na fila quando a geração mudou (um estilo novo) pulam o trabalho ao
  começar, em vez de rastrear um tile inteiro que seria descartado.

Medição (`DIORAMA_TESTS=style-swap`, Fabric 26.3, janela 1600×900, nesta máquina, 20/10/2026): o
cenário visita quatro lugares a 400 blocos para deixar 57 tiles residentes (109 MiB), volta ao ponto
fixo (8,5; 8,5), abre o livro em Aparência e clica no estilo. Tempo do clique até os 4 tiles da tela
mudarem, e até o fim da revisão inteira:

| Troca | Antes (tela = revisão inteira) | Depois: tela | Depois: revisão inteira |
| --- | --- | --- | --- |
| Contornos → Pintado | 4421 ms | **875 ms** | 4223 ms |
| Pintado → Contornos | 3148 ms | **448 ms** | 3150 ms |

O cenário falha se só parte dos tiles da tela mudar, se um tile da tela perder textura ou
cobertura, ou se o clique não escolher o estilo. O trabalho total não muda; só a ordem em que ele
fica visível. Com mais tiles na tela (zoom afastado, janela maior) o tempo cresce com eles, não com
os residentes.

Manter as texturas do estilo anterior para voltar instantaneamente foi avaliado e **não** adotado:
cada tile residente custa ~1,9 MiB de GPU (dia + noite), a volta já fica abaixo de meio segundo, e
o cache não pouparia nenhum bake: o disco e `appearance.bin` ainda precisariam dos pixels no estilo
escolhido, então os mesmos tiles seriam rastreados de qualquer jeito, agora com texturas fora do
orçamento de 256 MiB.

## Limites

- Caves, Nether e End também recebem tinta, nanquim e textura; neles a luz já é a do jogo e o
  passo de dois tons só remove a AO.
- Pixels históricos que não podem ser redesenhados (multiplayer, versões sem `SavedChunks`)
  mantêm a aparência com que foram salvos, como nos outros estilos.
- Muito afastado, os mipmaps médiam linhas e pinceladas; o que segura o estilo nessa escala são
  as áreas chapadas, a paleta e a água em aguadas.

## Validação

`DIORAMA_TESTS=painted DIORAMA_RENDER_DISTANCE=8 ./gradlew --configure-on-demand :26.3:runClientGameTest`
fotografa uma aldeia e uma costa nos três estilos de perto, o Pintado no zoom médio, o minimapa e a
noite (Fabric 26.3).
