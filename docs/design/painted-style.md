# Estilo de render pintado

Implementado em 20/10/2026 na branch `feature/painted-style`. A opção substitui o antigo toggle
de contornos: `Configurações → Aparência → Render` alterna entre **Simples** (sem contornos),
**Contornos** (o visual anterior e padrão) e **Pintado**. A preferência é a mesma para minimapa,
mapa mundial e tela do Mod Menu.

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
- **Sombras nítidas**: o sol não recebe o jitter por amostra, então a borda da sombra é uma mancha
  chapada (o supersampling ainda suaviza o serrilhado).
- **Paleta**: sol dourado, sombra lavanda‑azulada, um pouco mais de luz ambiente
  (`MapStyle.paint()`), e uma curva de pigmento em que nada chega ao preto puro nem estoura no
  branco (piso levemente violeta, ombro nos realces).
- **Plantas** sobre o chão pesam menos (0,22 em vez de 0,4), para o capim não salpicar o terreno.
- **Água em aguadas**: a profundidade vertical cai em quatro faixas (rasa < 1,25, < 3,5, < 8 e
  funda), cada uma mais escura e mostrando menos fundo; a cor do bioma é puxada 55% para um
  azul‑petróleo de mapa pintado.

### Acabamento (`PaintedFinish`)

Recebe o patch rastreado com 4 pixels de borda e devolve só o miolo:

1. **Tinta**: filtro de Kuwahara (janelas 4×4) aplicado a 90%: cada área vira a mancha lisa de um
   pincel e as bordas entre áreas se mantêm. É isso que derrete a textura dos blocos. O plano
   noturno usa a janela escolhida pelo diurno, para os dois ficarem em registro.
2. **Nanquim**: linha sépia quente atrás de cada borda mais próxima (peso pela diferença de
   profundidade), linha mais leve no lado escuro de fronteiras de cor nítidas, linha firme na terra
   ao longo de toda costa e anéis azulados onde a água muda de aguada. O traço varia de peso com um
   ruído de mundo, como pena à mão; a linha mais forte vence em vez de somar.
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
- O alcance extra (4 px, menos de um bloco) fica dentro das margens já usadas por
  `TileInvalidation`, então edições redesenham tudo o que afetam.
- Trocar de estilo preserva exploração e tiles residentes (cenário `settings-refresh`).
- Simples e Contornos continuam produzindo exatamente os pixels de antes; nada muda para quem não
  escolhe Pintado.

## Nitidez

Os tiles são ampliados com NEAREST sempre que um texel cobre pelo menos um pixel real de tela
(zoom × escala da GUI × tamanho do nível de overview). Antes a conta ignorava a escala da GUI: com
GUI 3×, zooms de 1 a 4 px de GUI por bloco mostravam texels de 1 a 3 pixels de tela com filtro
linear, e o mapa parecia borrado. Vale para todos os estilos, mapa mundial e minimapa. Abaixo de um
pixel de tela por texel os mipmaps continuam com filtro trilinear, para o mapa não cintilar ao
mover ou dar zoom.

## Migração

Configs antigas só têm `outlines`: `true` vira Contornos, `false` vira Simples, e o campo deixa de
ser gravado. O padrão para configs novas continua Contornos.

## Custo

O acabamento é uma passada linear sobre a área: as janelas do Kuwahara saem de somas de caixa
(contagem, luma e luma², cada janela somada a partir dos próprios pixels, sem janela deslizante,
para patch e tile darem o mesmo resultado), mais vizinhos e consultas de tabela. Medido num tile
inteiro de 512² com dia e noite (JUnit, melhor de 8, nesta máquina): ~79 ms, contra ~0,7 s de bake
médio por tile nos logs do teste de cliente — cerca de 10% a mais por tile no estilo pintado. As
tabelas de pincel, aguada e grão são geradas uma vez na carga da classe (256², 128², 128²).

## Limites

- Caves, Nether e End também recebem tinta, nanquim e textura; neles a luz já é a do jogo e o
  passo de dois tons só remove a AO.
- Pixels históricos que não podem ser redesenhados (multiplayer, versões sem `SavedChunks`)
  mantêm a aparência com que foram salvos, como nos outros estilos.
- Muito afastado, os mipmaps médiam linhas e pinceladas; o que segura o estilo nessa escala são
  as áreas chapadas, a paleta e a água em aguadas.

## Validação

`DIORAMA_TESTS=painted ./gradlew --configure-on-demand :26.3:runClientGameTest` fotografa uma
aldeia e uma costa em Contornos e Pintado, em três zooms, mais o minimapa e a noite (Fabric 26.3).
