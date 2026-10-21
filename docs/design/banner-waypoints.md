# Waypoints com estandartes — plano

Status: implementado em 20/10/2026 na branch `feature/banner-waypoints` (etapas 1–7). Escrito
como plano no mesmo dia; a seção "Como ficou" no fim registra onde a implementação se afastou do
plano.

## Objetivo

Cada waypoint passa a ser um estandarte (banner) do Minecraft, desenhado no mapa mundi
e no minimapa como um sprite em pixel art "diegético": um estandarte fincado no chão,
com mastro, travessa, tecido e sombra, no mesmo estilo pixel-perfect do fog. O desenho
é totalmente customizável como no tear vanilla: cor de base, padrões e cores dos
padrões, usando os padrões registrados no jogo (incluindo os de datapacks e mods).

## Como é hoje

- `Waypoint` guarda nome, iniciais, posição, dimensão, `color` (paleta de 16 cores),
  visibilidade e tipo (`MANUAL`, `DEATH`). `WaypointStore` salva tudo em
  `waypoints.json` na pasta do mundo via Gson (`saves/<mundo>/diorama/` no singleplayer,
  `.minecraft/diorama/<mundo>/` em servidores).
- `WaypointOverlay` desenha um círculo colorido com as iniciais, e uma seta na borda
  quando o waypoint está fora da vista. O mapa mundi e o minimapa usam o mesmo código.
- `WaypointEditScreen` edita nome, iniciais e cor. `WaypointHud` desenha no mundo.
  `WaypointShare` compartilha no formato de chat do Xaero, e `XaeroImport` importa.

## Modelo de dados

- Novo `BannerDesign` (record imutável): `DyeColor base` e `List<Layer>` com
  `Layer(String pattern, DyeColor color)`. O padrão é guardado pelo id
  (`minecraft:creeper`, `meumod:...`), não pelo índice do registro, então sobrevive a
  mudanças de datapack. Um id desconhecido é pulado ao desenhar e mantido ao salvar.
- `Waypoint.banner` opcional. Waypoints antigos não têm o campo: usam um estandarte
  liso com a cor de base mais próxima de `color`. Nada é regravado até o jogador editar.
  O JSON continua legível por versões anteriores, que ignoram o campo novo.
- Limite de camadas: 6, como o tear. Uma configuração permite até 16 para quem quiser
  mais detalhe ("totalmente customizável"); acima de 6, o estandarte ainda é válido no
  mod, só não pode ser copiado para um item vanilla.
- Waypoints de morte usam um estandarte padrão próprio (preto com caveira e borda),
  editável como qualquer outro.

## Composição do sprite (CPU)

1. Lê as máscaras do tecido em `textures/entity/banner/<asset>.png` pelo gerenciador de
   recursos, então resource packs mudam o visual. A frente do estandarte ocupa 20×40
   texels da textura de entidade. O caminho de cada padrão vem do `assetId` do registro
   `BANNER_PATTERN`; diferenças entre versões ficam isoladas com Stonecutter.
2. Compõe como o `BannerRenderer`: base tingida, cada camada é uma máscara de alfa
   tingida com a cor da tinta, combinada em ordem.
3. Gera duas resoluções em pixel art, reduzindo por média e depois quantizando para as
   cores presentes, para o desenho continuar nítido:
   - mapa mundi: tecido 10×20 (metade da textura), estandarte completo ~14×26;
   - minimapa: tecido 6×12, estandarte completo ~9×16.
4. Moldura diegética desenhada à mão: mastro e travessa de madeira com contorno de
   tinta escura, ponta do mastro, sombra de 1 px no tecido do lado oposto ao sol do
   mapa e uma sombra pontilhada no chão.
5. Ondulação: 4 quadros pré-compostos em que colunas do tecido descem 0–1 px numa
   onda que corre do mastro para fora. Troca de quadro a cada ~250 ms, sem shader novo.
   Respeita uma opção "Animar estandartes" para quem preferir estático.

Os sprites entram num atlas `MapTexture` de 256×256 (256 KiB), com cache LRU por hash
do desenho (no máximo 128 desenhos, 4 quadros e 2 tamanhos cada). Recompõe só quando um
desenho muda ou os recursos recarregam (gancho junto de `FogTextures.reload()`).

## Desenho no mapa

- Mapa mundi: o pé do mastro fica exatamente sobre o bloco do waypoint. Escala inteira
  de pixels da GUI (1x, ou 2x no zoom aproximado), centrada em pixels inteiros, como o
  marcador do jogador. Nome numa plaquinha de pergaminho ao passar o mouse ou com zoom ≥ 2.
- Minimapa: a versão pequena. Fora da vista, o estandarte vai para a borda, um pouco
  menor, com a seta atual apontando a direção.
- Sobreposição: estandartes mais ao sul ficam na frente. Quando ficam a menos de meio
  sprite um do outro, juntam-se num grupo com o número de waypoints.
- Hover e clique continuam pelas mesmas áreas de `WaypointOverlay.Pin`, agora do tamanho
  do sprite.
- Estilo do fog: no mapa antigo em sépia, uma opção "Estandartes em sépia" passa o
  sprite pela mesma rampa do fog; por padrão eles mantêm as cores, para se destacar.
- No mundo (`WaypointHud`): o ícone usa o mesmo sprite pequeno no lugar do círculo.

## Editor (estilo tear)

Uma tela `BannerEditScreen`, aberta pelo `WaypointEditScreen`:

- Prévia grande do estandarte em escala inteira (4x), com o mastro, girando entre os
  quadros da ondulação.
- 16 amostras de tinta para a base e para a camada selecionada.
- Grade rolável com todos os padrões registrados, cada miniatura já tingida com a cor
  escolhida, com o nome traduzido no tooltip.
- Lista de camadas: adicionar, remover, mover para cima e para baixo, trocar cor.
- Desfazer e refazer. Botão "Aleatório". Botão "Copiar do estandarte na mão": lê o
  componente de camadas e a cor de base do item segurado (ou do primeiro estandarte do
  inventário). Botão "Copiar para o item" fica fora do escopo (exigiria servidor).
- Teclado e controle: navegação por setas e foco visível, como as outras telas do mod.

## Compartilhar e importar

- `WaypointShare`: acrescenta o desenho no fim da linha de chat num campo compacto
  (base e camadas em base64 de ids curtos). Quem não tem o campo, ou usa o Xaero,
  recebe o waypoint com a cor de sempre.
- `XaeroImport`: cria estandartes lisos com a cor mais próxima.

## Etapas

1. `BannerDesign`, migração de `color`, serialização Gson e testes JUnit (ida e volta,
   arquivo antigo, id desconhecido).
2. Compositor no CPU e atlas com cache; testes JUnit com máscaras sintéticas (ordem
   das camadas, tingimento, redução e quantização).
3. Desenho no mapa mundi e no minimapa, borda, grupos, hover; marcador de morte.
4. Editor estilo tear e importação do item segurado.
5. Ondulação, opção de sépia, ícone no HUD.
6. Compartilhamento por chat e importação do Xaero.
7. Documentação (README e este arquivo) e um único cenário de cliente
   `DIORAMA_TESTS=banner-waypoints` com capturas do mapa, do minimapa e do editor.

As etapas 1–3 já entregam o visual novo. O editor (4) é a maior parte do trabalho.

## Compatibilidade e riscos

- A API de padrões de estandarte mudou entre versões (registro de dados desde 1.20.5,
  renomeações em 26.x). Todo o acesso ao registro e às texturas fica numa classe
  isolada com blocos Stonecutter; compilar Fabric e NeoForge 26.3 e 1.21.1.
- Resource packs com texturas de estandarte maiores (32x, 64x): a máscara é lida na
  resolução que vier e reduzida para o mesmo tamanho do sprite.
- Multiplayer: tudo é do cliente; não precisa de servidor.
- Performance: composição só quando o desenho muda; desenho do mapa são quads de um
  atlas, como os ícones de hoje. Sem textura nova por waypoint.
- Arquivos de waypoints continuam compatíveis nos dois sentidos.

## Como ficou

- Classes: `waypoint/BannerDesign` (dados puros, tintas próprias com as cores de
  `DyeColor.getTextureDiffuseColor()`), `waypoint/WaypointJson` (Gson; o campo `banner` só é
  escrito quando existe), `waypoint/BannerPatternCatalog` (todo acesso ao registro
  `BANNER_PATTERN`, às texturas e ao item na mão), `render/BannerCompositor` (composição, redução,
  quantização, moldura e ondulação, sem classes do jogo) e `render/BannerAtlas` (atlas e cache).
- Tamanhos reais: mapa mundi 14×30 (tecido 10×20), minimapa/HUD/lista 10×21 (tecido 6×12). O pé do
  mastro fica entre as duas colunas de madeira, em pixels inteiros. 2x a partir de zoom 4 no mapa
  mundi. Nomes em plaquinha de pergaminho: no mapa mundi só com o mouse sobre o estandarte; no
  minimapa, acima do estandarte, para waypoints a até 10 blocos do jogador.
- A paleta da quantização é a tinta de cada camada multiplicada pela cor média da sua máscara.
- Atlas: 256×256 não comportava 128 desenhos × 4 quadros × 2 tamanhos. Ficou 512×512 (1 MiB) com
  48 vagas LRU de 80×64 (4 quadros de onda + 1 parado, nos dois tamanhos); as escritas são
  quadrados 16×16. No máximo 6 desenhos novos são compostos por quadro; enquanto isso o waypoint
  aparece como um ponto da cor da base. O editor usa um atlas próprio, liberado ao fechar.
  `BannerAtlas.reload()` roda junto de `FogTextures.reload()`.
- Ondulação: a metade de baixo do tecido desce 1 px em colunas alternadas de duas, correndo com o
  vento da esquerda para a direita; a opção "Ondular" desligada usa o quadro parado. Luz vinda de
  cima à esquerda: borda direita e linha sob a travessa sombreadas.
- Sépia: opção "Sépia no mapa antigo", aplicada só com o estilo Mapa antigo (não o colorido), com a
  mesma rampa `sepia()` do `fog.fsh`, calculada no CPU como variante do desenho no atlas.
- Editor: todos os controles são botões vanilla (foco visível com Tab/setas/controle); as setas
  rolam a grade ao passar da primeira/última linha; Ctrl+Z, Ctrl+Y e Ctrl+Shift+Z. Clicar num
  padrão adiciona uma camada com a "tinta do padrão"; escolher uma tinta com uma camada selecionada
  a recolore. As miniaturas usam a base atual (ou branco/cinza quando a tinta é igual à base).
- `WaypointEditScreen`: as 16 amostras agora são as tintas da base do estandarte; "Editar
  estandarte…" abre o editor. Um estandarte que não mudou não é gravado; `color` continua sendo
  salvo (a tinta da base quando muda) para versões antigas e para o Xaero.
- Chat: ` diorama-banner:<base64>` no fim da linha quando o desenho não é o liso da cor; o nome é
  encurtado até 12 caracteres para caber, senão o campo é omitido. O link [Add] usa
  `/diorama waypoint add-banner …`; `add` continua igual.
- Testes: `BannerDesignTest` e `BannerCompositorTest` (JUnit) e o cenário
  `DIORAMA_TESTS=banner-waypoints` com as capturas `banner-map`, `banner-map-close`,
  `banner-map-sepia`, `banner-minimap` e `banner-editor`.
- Fora do escopo, como no plano: copiar o desenho para um item.
