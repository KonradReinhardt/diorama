# Mapa pixel perfect na grade de blocos

Implementado em 20/10/2026 na branch `feature/pixel-grid`. Vale para os três estilos de render
(Simples, Contornos e Pintado), mapa mundial e minimapa, em todos os alvos.

## Problema

Com a câmera a 50° e 4 px por bloco na horizontal, o topo de um bloco ocupava ~3,06 linhas de
pixel e a lateral ~2,57. As faces alternavam entre 3 e 4 linhas, e as 4 amostras por pixel
misturavam faces vizinhas nas bordas e suavizavam as sombras em meio‑pixels. Na tela, um texel
ampliado por um fator não inteiro (2,4 px de tela no zoom 3,2 com GUI 3×, por exemplo) também
saía com larguras alternadas. O contorno comparava profundidades médias com um limiar, então
desenhava o relevo do fundo através da água e linhas tremidas onde a média oscilava.

## Projeção

`v = z · zScale − y · yScale` com `zScale = 1` e `yScale = cot(pitch)` (`Projection`). É a vista
inclinada de antes esticada na vertical, de modo que o topo de um bloco sempre ocupa uma unidade.
O pitch padrão passou de 50° para 45°: aí a lateral também ocupa uma unidade, então **toda face
visível é um quadrado de 4×4 pixels**, toda aresta de bloco cai numa borda de pixel e cada célula
de 4×4 px da grade mostra exatamente uma face. A vista continua oblíqua, com as laterais
aparecendo, e a escala horizontal não muda.

O raio de cada pixel anda `zScale` para baixo e `yScale` para o norte por passo; a profundidade da
água atravessada, medida nesse passo, já é vertical.

## Bake

- **Um raio por pixel, no centro.** Como o pixel está inteiro dentro de uma face, as 4 amostras de
  antialiasing não acrescentam nada além de borrar sombra e luz. Sombras, AO e oclusão de céu
  passam a ser avaliadas por texel, sem o jitter do sol. O bake fica cerca de 4× mais rápido (o
  tile médio no teste de cliente caiu de ~0,7 s para ~0,2–0,34 s).
- A Qualidade só afeta a névoa agora: os dois modos geram os mesmos pixels, então ela saiu de
  `BakeContext.appearance()` e trocar a Qualidade não redesenha o mapa.
- `LOOK_REVISION` 3.

## Contorno geométrico

Cada raio registra a face que o pixel mostra: topo (plano `y`) ou sul (plano `z`). Onde o raio
atravessou água, a face é a **superfície da água**, nunca o fundo. Para cada par de pixels vizinhos
(direita e abaixo), `TileBaker.edges` calcula onde os dois planos estão na linha de tela entre eles
(`faceDepth`):

- se coincidem, as faces se encontram (topo e sua lateral, chão encostando numa parede) e não há
  linha;
- se não, o pixel mais distante fica **atrás** (`behind`) do outro pela diferença, em blocos ao
  longo da vista, e o mais próximo fica **na frente** (`front`).

O resultado é exato no pixel, sem média nem ruído: um degrau de 1 bloco dá 1,41, de 2 blocos 2,83.
Contornos escurece (0,38) os pixels atrás de um degrau de 2 blocos ou mais (`OUTLINE_DEPTH` 1,5,
como antes); o Pintado usa `behind` para o peso do nanquim e `front` para o brilho de borda. Linhas
só nos 4 vizinhos, então nunca engrossam nas quinas. Dentro da água não há contorno algum; a costa
continua com a linha e a espuma do Pintado.

## Pintado na grade

A tinta (Kuwahara) passou a trabalhar por **face**: cada célula de 4×4 px tem o albedo médio de
seus pixels de terra, e o dab vem da janela de 2×2 faces que menos varia. Cada pixel é multiplicado
pela razão entre o dab e o seu albedo, então a cor da face muda e a luz por texel (sombras) fica
intacta, e nenhuma borda sai da grade. As linhas entre cores só aparecem entre faces diferentes. A
borda do patch é de 2 faces (8 px) e precisa começar numa borda de face: `PaintedFinish.apply`
recusa patches fora da grade.

## Exibição pixel perfect

`MapViewport.View.pixelPerfect()`: quando um texel cobre pelo menos um pixel de tela, a vista
desenhada arredonda o zoom para um número inteiro de pixels de tela por texel e desloca o centro
menos de meio pixel, para cada borda de texel cair numa borda de pixel. O `MapScreen` e o minimapa
desenham com essa vista; o zoom suave e o arrasto continuam guardando os valores exatos, e tiles,
névoa, radar, waypoints e o marcador usam todos a mesma vista. Abaixo de um pixel de tela por texel
nada muda (mipmaps trilineares).

## Mapas salvos

Pixels da projeção anterior não podem ser reprojetados sem a geometria, então a grade grava em
pastas novas: `surface-grid-45` e `under-roof-grid-45-…` (mais o sufixo de direção). As pastas
antigas (`surface`, `surface-50`, `under-roof-50…`) ficam intactas no disco e não são apagadas. O
mapa da grade começa vazio e é preenchido explorando de novo. Uma migração futura poderia, no
singleplayer 26.3, traçar os raios antigos pelos `SavedChunks` para descobrir quais colunas já
foram exploradas e redesenhá‑las na grade.

## Validação

- JUnit `BlockGridTest`: toda face tem 4×4 px e toda aresta cai em pixel inteiro; faces que se
  encontram têm a mesma profundidade e um degrau de 2 blocos dá 2√2; `pixelPerfect` dá um número
  inteiro de pixels de tela por texel, alinha as bordas e move o centro menos de meio pixel.
- JUnit `PaintedFinishTest`: patch == tile inteiro, alpha preservado, patch fora da grade recusado.
- `DIORAMA_TESTS=painted DIORAMA_RENDER_DISTANCE=8 ./gradlew --configure-on-demand :26.3:runClientGameTest`
  fotografa aldeia e costa nos três estilos de perto, o Pintado no zoom médio, minimapa e noite
  (~5,5 min, contra ~11 min antes da grade e com distância 12).
