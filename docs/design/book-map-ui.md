# Mapa mundi com interface de livro — plano

Status: plano escrito em 20/10/2026 e revisado no mesmo dia depois do estilo de render Pintado e
da correção de nitidez (`feature/painted-style`), e de novo depois da grade pixel perfect, do
Pintado sem contornos e da opção de tingir o explorado no mapa antigo (`feature/pixel-grid`).
Implementado em 20/10/2026 na branch `feature/book-map-ui` (etapas 1–8); a seção "Como ficou" no
fim registra onde a implementação se afastou do plano.

## Objetivo

Reformular os menus do mapa mundi para uma interface imersiva de livro em pixel art (páginas de
pergaminho quente, contorno de tinta marrom, capa de couro, cantoneiras douradas), sem deixar de
mostrar o mapa em tela cheia:

- O mapa continua ocupando a tela inteira. Os menus são um livro aberto por cima dele.
- A navegação é feita por marcadores (fitas) laterais coloridos, cada um com o ícone de um item
  do Minecraft desenhado pelo renderizador de itens do jogo, então resource packs mudam os ícones.
- Opções e waypoints/estandartes ficam em seções separadas do livro.
- Configurações que afetam o mapa mundi são alteradas dentro do próprio mapa mundi, sem trocar de
  tela. Só as configurações exclusivas do minimapa saem para outra tela.
- Uma animação curta de zoom ao abrir o mapa mundi.

A imagem de referência define a estética, não os pixels: todos os sprites são arte original do mod.

## Como é hoje

- `MapScreen`: mapa em tela cheia, faixa preta de status no topo, faixa de ajuda embaixo e um
  botão vanilla "Settings" no canto superior direito que troca para `SettingsScreen(this)`.
  Clique em estandarte abre `WaypointEditScreen`; botão direito cria waypoint; tecla U abre
  `WaypointListScreen`; C, PgUp/PgDn e Espaço controlam cavernas, fatia e recentralização.
- `SettingsScreen`: todas as opções em três colunas de botões vanilla que alternam valores, com
  layout compacto para 427×240. Também é a tela do Mod Menu e da tecla de configurações. Em
  Aparência, o antigo toggle "Contornos" virou o ciclo **Render** (`Settings.renderStyle`:
  `PLAIN`/`OUTLINED`/`PAINTED`, padrão `OUTLINED`, com tooltip); configs antigas migram do campo
  privado `outlines`, que não é mais gravado.
- Estilo **Pintado** (`docs/design/painted-style.md`): o terreno vira mapa de fantasia pintado à
  mão no bake (`TileBaker` + `PaintedFinish`) — luz chapada em dois tons, tinta sépia sutil, costas
  com espuma, água em aguadas, pinceladas e grão de papel. Trocar de Render redesenha o mapa
  inteiro em segundo plano pelo refresh atômico existente, sem perder a exploração.
- Tiles do mapa são ampliados com NEAREST sempre que um texel cobre ≥ 1 pixel de tela
  (`MapViewport.magnified`, que conta a escala da GUI e o nível de overview); abaixo disso, mipmaps
  trilineares. Antes o mapa ficava borrado com GUI 2× ou 3×.
- `WaypointListScreen`: lista com Editar/Ocultar/Excluir/TP e importação do Xaero.
- Já existem peças reaproveitáveis: `Spring` (animação por mola), `BannerAtlas`/`BannerCompositor`
  (estandartes em pixel art), `PaperFrame` (leitura de texturas do resource pack), `SmoothZoom`.

## Organização do livro

### Estados

- **Fechado** (padrão ao abrir o mapa): mapa livre em tela cheia; só as fitas aparecem saindo da
  borda direita da tela, como marcadores para fora das páginas.
- **Aberto**: o livro (duas páginas) aparece sobre o mapa; as fitas passam a sair da borda direita
  do livro, e a fita da seção atual fica mais comprida e "presa" à página.
- O livro lembra a última seção e o waypoint selecionado enquanto o jogo estiver aberto (estado
  estático em memória, não salvo em disco). Voltar de uma tela filha (editor de estandarte,
  configurações do minimapa) reabre o livro na mesma seção, sem repetir a animação de abertura.

### Marcadores (fitas)

| Fita | Cor | Item (ícone) | Conteúdo |
|---|---|---|---|
| Estandartes | vermelho | `minecraft:red_banner` | lista de waypoints + opções de waypoints e estandartes |
| Aparência | azul | `minecraft:filled_map` | render (Simples/Contornos/Pintado/Pintado sem contornos), estilo do fog, tingir explorado (mapa antigo), cartografia da seed, fog sempre, fog arredondado, sombreamento, qualidade, iluminação, direção |
| Camadas | verde | `minecraft:lantern` | superfície/caverna, fatia Y (− / +), grade de chunks, slime chunks, pontos de spawn |
| Radar | amarelo | `minecraft:spyglass` | jogadores, hostis, neutros, passivos (com as carinhas de `MobFaces`), alcance |
| Minimapa | creme | `minecraft:compass` | sai para a tela de opções do minimapa (com um pequeno sinal de "sair" na fita) |

Além das fitas: um "X" no canto do livro (como na referência) fecha o livro, e não o mapa.

Seções com mais conteúdo que duas páginas usam as abas do topo do livro (como as quatro abas da
referência), também com ícones de item. Previsto só para Estandartes: **Lista**
(`minecraft:red_banner`) e **Opções** (`minecraft:loom`).

### Classificação das configurações

Regra: tudo o que muda o que o mapa mundi mostra fica no livro, inclusive o que vale também para o
minimapa (radar, sobreposições, aparência, waypoints). Sai do mapa somente o que é exclusivo do
minimapa:

- **No livro**: `renderStyle`, `fogStyle`, `antiqueTintsExplored`, `seedPreview`, `alwaysFogOfWar`, `roundedFog`,
  `depthShading`, `quality`, `lighting` (quando `EnvironmentLighting.supported()`), `mapDirection`,
  `chunkGrid`, `slimeChunks`, `spawnSpots`, `radar*`, `waypointsOnMap`, `waypointsInWorld`,
  `deathMarkers`, `animateBanners`, `sepiaBanners`, `extraBannerLayers`.
- **Fora (fita Minimapa)**: `minimap`, `minimapSize`, `minimapCorner`, `minimapFrame`,
  `minimapZoom`, `compass`, `coordinates`, `biome`, `autoCaves` (confirmar ao implementar se
  `autoCaves` afeta o nível inicial do mapa mundi via `MapSession.autoLayer`; se afetar, vai para
  Camadas).

As mudanças valem ao vivo: o livro chama `Settings.save()` como hoje, e `MapSession` continua
juntando refresh de qualidade/aparência no próximo tick, preservando exploração e texturas
residentes pelas barreiras existentes. Nenhum rebake extra por estar no livro. `quality` não
redesenha mais o mapa (com a grade, um raio por pixel nos dois modos; só a névoa muda), e
`antiqueTintsExplored`, `fogStyle`, `roundedFog` e `lighting` são só composição, então mudam na
hora. `renderStyle` e `depthShading` redesenham o mapa inteiro em segundo plano a cada troca, então o
livro só aplica no clique (nada de pré-visualizar ao passar o mouse) e o ciclo de Render não
deve passar por valores intermediários: os espaços de Render escolhem o valor direto.

### Conteúdo das páginas

- **Estandartes / Lista**: página esquerda com grade 4×3 de espaços (como a referência), um
  estandarte por espaço (`BannerAtlas`, tamanho pequeno), ordenados por distância e paginados;
  área de baixo com "Novo", filtro de ocultos e "Importar do Xaero (N)" quando houver. Página
  direita com o waypoint selecionado: estandarte grande ondulando, nome editável na própria
  página, coordenadas, distância e ações (Centralizar no mapa, Editar estandarte, Ocultar/Mostrar,
  Compartilhar, Teleportar com permissão, Excluir com confirmação no segundo clique).
- **Estandartes / Opções**: as seis opções de waypoints/estandartes.
- **Aparência**: render como quatro espaços com miniaturas estáticas (Simples, Contornos,
  Pintado, Pintado sem contornos), no topo da página por ser a escolha mais visível; estilo do fog
  como três espaços (Nuvens, Mapa antigo, Mapa antigo colorido), com a alternância "tingir
  explorado" logo abaixo, desabilitada com dica quando o estilo é Nuvens (ela só vale para os dois
  mapas antigos); direção como uma rosa dos ventos de quatro
  espaços; o resto como alternâncias e ciclos. As miniaturas são sprites fixos gerados pelo
  `book-sprites.py` a partir de capturas de `DIORAMA_TESTS=painted` reduzidas à mão para pixel
  art, não tiles ao vivo. O Pintado combina com o livro (tinta sépia, papel quente): a cor de
  tinta `#5A2E22` da paleta do livro pode ser conferida contra o `INK` de `PaintedFinish` para os
  dois parecerem a mesma pena.
- **Camadas**: modo superfície/caverna e fatia Y com botões (o equivalente de mouse para C e
  PgUp/PgDn); sobreposições. Slime chunks aparece desabilitado com dica quando não há seed.
- **Radar**: quatro categorias com ícones e o alcance.

O editor de estandarte estilo tear (`BannerEditScreen`) continua sendo uma tela própria, porque
é grande demais para uma página; ele volta ao `MapScreen` com o livro aberto. `WaypointEditScreen`
e `WaypointListScreen` continuam existindo para uso fora do mapa (tecla de novo waypoint no mundo,
tecla da lista fora do mapa, testes existentes).

### Integração com o mapa

- O mapa continua interativo com o livro aberto: arrastar, rolar e botão direito fora do livro
  funcionam como hoje, para ver o efeito das opções ao vivo. Eventos dentro do livro vão só aos
  widgets do livro.
- Clique num estandarte do mapa: abre a seção Estandartes com ele selecionado (em vez de abrir
  `WaypointEditScreen`). Botão direito no mapa: cria o waypoint e seleciona na lista com o nome em
  edição.
- "Centralizar no mapa" anima a vista até o waypoint pelo `SmoothZoom`/centro existente.
- Esc fecha o livro primeiro; um segundo Esc fecha o mapa. Tecla U (lista) dentro do mapa abre a
  seção Estandartes. Com um campo de texto focado, as teclas do mapa (C, Espaço, PgUp/PgDn, tecla
  do mapa) não podem disparar.
- A faixa preta de status vira uma pequena etiqueta de pergaminho no canto superior esquerdo
  (modo, coordenadas, chunks, "assando"). A faixa de ajuda vira o tooltip de um "?" discreto no
  canto inferior, mostrado sozinho por alguns segundos só na primeira abertura da sessão.
- O botão vanilla "Settings" sai do `MapScreen`.

## Arquitetura

Código novo em `dev.diorama.ui` (comum, sem APIs de loader):

- `SettingsCatalog`: lista declarativa das opções (`chave`, `seção`, `escopo` MAPA/MINIMAPA,
  valores, getter/setter, rótulo do valor, tooltip, disponibilidade). Fonte única para
  `SettingsScreen`, livro e Mod Menu, mantendo as opções compartilhadas como pede o projeto.
  `SettingsScreen` passa a ser montada pelo catálogo, com um filtro opcional de seção
  (`new SettingsScreen(parent, Section.MINIMAP)` para a fita Minimapa); sem filtro continua
  mostrando tudo para o Mod Menu e para a tecla de configurações.
- `BookLayout` (puro, testável): dado o tamanho da GUI, retorna retângulos do livro, páginas,
  fitas, abas e "X". Alvo de ~320×200 GUI px para as duas páginas; abaixo de ~360×210 usa modo de
  página única com setas de virar página. Tudo em pixels inteiros.
- `MapBook`: estado (fechado/abrindo/aberto/fechando, seção, aba, página, seleção), roteamento de
  entrada e desenho; o `MapScreen` só delega a ele.
- Páginas: `AppearancePage`, `LayersPage`, `RadarPage`, `BannersPage` (lista + detalhe) e
  `BannerOptionsPage`, montadas a partir do catálogo.
- Widgets em pixel art: `BookToggle` (caixa de tinta/selo), `BookCycle` (setas ‹ › com valor),
  `BookButton`, `BookSlot` (espaço da grade), `Ribbon` e `BookTab`. Estendem `AbstractWidget`
  /`AbstractButton` para manter foco por Tab/setas/controle e narração, como as telas atuais.
- `WaypointListModel`: ordenação por distância, permissão de TP e importação do Xaero extraídas de
  `WaypointListScreen`, usadas pela tela antiga e pelo livro. `WaypointDraft`: validação de nome e
  coordenadas extraída de `WaypointEditScreen` para a edição inline.
- `BookSprites`: único ponto de contato com APIs de GUI que mudam entre versões (`blitSprite` e
  desenho de `ItemStack` na GUI), isolado com blocos Stonecutter, como `PaperFrame` faz com
  `NativeImage`.

### Sprites e resource packs

- Sprites próprios em `assets/diorama/textures/gui/sprites/book/` (capa, página esquerda/direita,
  lombada, espaço, separador, fita base, aba, "X", alternância, setas), com `.png.mcmeta` de
  `nine_slice`/`tile` do atlas de GUI. Resource packs podem substituir cada um.
- A fita é um sprite neutro tingido pela cor da seção, para um pack trocar uma única textura.
- Os ícones são `ItemStack` reais desenhados pelo renderizador de itens: seguem modelos de item
  de resource packs.
- Os PNGs são gerados por um script reproduzível `docs/design/book-sprites.py`, no mesmo espírito
  de `antique-sprites.py`, com a paleta: página `#F5C99B`, espaço `#EAB384`, tinta `#5A2E22`,
  couro `#9C5233`, ouro `#E8B83A`, fitas vermelho/azul/verde/amarelo/creme saturados.
- Um texel = um pixel da GUI, como a GUI vanilla. Nenhuma textura dinâmica nova: só sprites do
  atlas de GUI e o `BannerAtlas` existente (com seu limite de 6 composições por quadro).
- Sons vanilla de livro (`BOOK_PAGE_TURN` ao trocar de seção) para imersão; respeitam os volumes.

## Animações

Todas curtas, por tempo real e interrompíveis:

- **Zoom de entrada (~200 ms)**: classe pura `OpeningZoom` com ease-out cúbico de um fator 0,85
  até 1 sobre o zoom alvo da vista, ancorado no jogador (ou no centro quando não segue). Começa em
  `max(minZoom, alvo × 0,85)`, então nunca pede zoom abaixo do que o nível suporta. A vista um
  pouco mais aberta no início pede no máximo ~38% mais área, quase sempre já residente; nenhum
  rebake. Roda só na primeira `init()` da instância (não em resize nem ao voltar de tela filha),
  termina na hora ao rolar, arrastar, `lookAt()` (testes) ou mudança de direção, e não mexe no
  alvo do `SmoothZoom`. As fitas deslizam da borda no mesmo intervalo. A vista desenhada só
  encaixa em pixels inteiros de tela por texel (`View.pixelPerfect`) quando o zoom está parado
  (`SmoothZoom.moving()` falso); a abertura precisa contar como "em movimento" para não andar aos
  saltos, e o alvo deve ser um passo inteiro de pixels (como a roda faz ao ampliar), para não haver
  "estalo" ao terminar. Se o fator cruzar o limite de `MapViewport.magnified` (um texel = um pixel
  de tela), a amostragem troca de trilinear para NEAREST no meio da animação; conferir nas capturas.
- **Abrir/fechar o livro (~180 ms)**: `Spring.SOFT` — o livro sobe alguns pixels e aparece.
- **Trocar de seção (~120 ms)**: o conteúdo desliza levemente como uma página virando.
- **Fitas**: passar o mouse estica a fita 3 px (`Spring.BOUNCY`); a selecionada fica mais longa.

## Etapas

0. Antes de começar: estandartes, nitidez e Pintado já estão em `development` (merge
   `6d245c3`); a grade pixel perfect, o zoom em passos inteiros, o Pintado sem contornos e a
   opção de tingir o explorado estão em `feature/pixel-grid`, em cima dela. A branch nova
   `feature/book-map-ui` sai de `development` depois de integrar `feature/pixel-grid` (ou dela
   mesma, se ainda não tiver entrado), para o livro já usar as opções novas.
1. `SettingsCatalog` e `SettingsScreen` montada por ele, sem mudança visual; filtro por seção.
   Testes JUnit do catálogo.
2. `BookSprites` com Stonecutter, sprites gerados pelo script, `BookLayout` e widgets do livro.
   Testes JUnit do layout.
3. `MapBook` no `MapScreen`: estados, fitas, roteamento de entrada, Esc, remoção do botão
   Settings, etiqueta de status e ajuda.
4. Páginas Aparência, Camadas e Radar a partir do catálogo, com efeito ao vivo.
5. Seção Estandartes: `WaypointListModel`, `WaypointDraft`, grade, detalhe, edição inline,
   integração com clique/botão direito/centralizar, importação do Xaero, ida e volta ao
   `BannerEditScreen`.
6. Fita Minimapa: `SettingsScreen(MapScreen, MINIMAP)` e volta com o livro na mesma seção.
7. `OpeningZoom` e animações do livro com `Spring`.
8. Testes de cliente, compilação cruzada, documentação (README, este arquivo, `CLAUDE.md`,
   `en_us.json` e `pt_br.json`).

## Verificação prevista

- JUnit (`./gradlew --configure-on-demand :26.3:build`):
  - `SettingsCatalog`: todo campo público de `Settings` aparece uma vez (o campo privado legado
    `outlines`, só lido na migração, fica de fora); os de minimapa têm escopo MINIMAPA; toda chave
    de tradução existe em `en_us.json` e `pt_br.json` (inclusive `render_style.*` e
    `antique_tint`).
  - `BookLayout`: em 427×240, 640×360 e 960×540 nada sai da tela nem se sobrepõe; o modo de página
    única entra abaixo do limite.
  - `OpeningZoom`: começa no fator, termina exatamente no alvo, é monótono e para ao cancelar.
- Cenário de cliente novo `DIORAMA_TESTS=book-map`: abre o mapa e espera o fim do zoom (zoom
  final igual ao alvo); captura cada seção; alterna uma opção de aparência no livro e confere
  `config.json`, `MapStyle` e que a tela continua sendo `MapScreen`; ida e volta pela fita
  Minimapa sem repetir o zoom; clique num estandarte abre a seção com ele selecionado; renomeia
  inline; repete a checagem de sobreposição em 1280×720; digitar "c" no nome não troca a camada.
- No `book-map`, trocar Render para Pintado pelo livro e conferir que `MapStyle.get().painted()`
  liga, que o mapa atrás continua com cobertura e que a tela continua sendo `MapScreen`; alternar
  "tingir explorado" e conferir que `submittedJobs` não muda. Medir zoom e âncora pela
  `MapScreen.heldView()`, porque `view()` devolve a vista já encaixada em pixels inteiros.
- Rodar também `settings-refresh` (que já alterna o Pintado), `painted`, `modmenu` (a tela do Mod
  Menu continua completa) e `banner-waypoints`, que tocam as telas alteradas.
- Compilar os alvos com APIs de GUI diferentes: Fabric 26.3, NeoForge 26.3, 26.1, 1.21.11,
  1.21.6, 1.21.4 e 1.21.1 (assinaturas de `blitSprite` e de desenho de item mudam entre eles).

## Riscos e limites

- APIs de GUI de sprites e itens mudam entre 1.21.1, 1.21.2–1.21.5, 1.21.6+ e 26.x: tudo passa por
  `BookSprites`; conferir nas fontes mapeadas de cada alvo antes de escrever os blocos.
- Telas pequenas (427×240): o livro cobre quase toda a altura; o modo de página única evita texto
  apertado.
- O mapa continua renderizando inteiro por trás do livro; o custo extra é algumas dezenas de quads
  de sprite e até 12 estandartes do atlas existente.
- `XaeroImport.find()` faz I/O; hoje roda no `init()` da lista. No livro, rodar só ao abrir a
  seção Estandartes pela primeira vez, e de preferência num worker, para não travar a thread de
  render.
- Fora do escopo: estilo de livro no `SettingsScreen` do minimapa/Mod Menu e no `BannerEditScreen`
  (podem vir depois reaproveitando `BookSprites`).

## Decisões em aberto

1. Clique fora do livro: proposto manter a interação com o mapa (arrastar/zoom/botão direito)
   em vez de fechar o livro.
2. Edição de waypoint: proposto inline no livro, com só o editor de tear em tela própria.
3. Tela de opções do minimapa: proposto manter a tela vanilla filtrada agora, sem estilo de livro.

## Como ficou

- **Código**: `ui/SettingsCatalog` (lista única; cada opção tem `shown` — a versão suporta — e
  `active` — outra opção a deixa sem efeito, como o tingimento sob as Nuvens), `ui/MapBook`
  (estado, fitas, animação, desenho, entrada), `ui/PageFlow` (distribui o conteúdo pelas páginas),
  `ui/BookPages` (Aparência, Camadas, Radar, Opções dos estandartes), `ui/BannersPage`,
  `ui/BookLayout`, `ui/BookWidget` (base com todos os blocos de versão de widget),
  `ui/BookWidgets`, `ui/BookSprites`, `ui/OpeningZoom`, `waypoint/WaypointDraft` e
  `waypoint/WaypointListModel`. `SettingsScreen` é montada pelo catálogo, com o mesmo layout de
  três colunas, e aceita só algumas seções numa coluna (`new SettingsScreen(map, Section.MINIMAP)`).
- **Sprites**: em vez de sprites do atlas de GUI com `.mcmeta`, uma única folha
  `assets/diorama/textures/gui/book.png` (256×256), gerada por `docs/design/book-sprites.py` sem
  dependências, desenhada por `MapCanvas.blit` com nine-slice próprio (cantos fixos, bordas e miolo
  repetidos, um texel = um pixel da GUI). Motivo: `blitSprite` muda de assinatura em quatro grupos
  de versões, enquanto `MapCanvas` já resolve todos. Resource packs trocam a folha inteira; as
  regiões ficam listadas em `BookSprites`. Capa e página têm 64×64 para o miolo custar poucos quads.
- **Miniaturas** de Render e de estilo do fog são desenhadas proceduralmente pelo script (uma ilha
  de blocos 4×4 em cada estilo), não reduzidas de capturas.
- **Ícones**: itens buscados pelo id no registro (`BookSprites.stack`), porque no 26.x os
  estandartes viraram uma `ColorCollection` em `Items`. A fita de Estandartes usa `white_banner`:
  o vermelho sumia na fita vermelha. Radar: `player_head`, `creeper_head`, `wolf_spawn_egg`,
  `cow_spawn_egg`. Camadas: `grass_block` e `lantern` nas duas vistas.
- **Camadas de desenho**: o livro começa num estrato novo (`nextStratum()` a partir de 1.21.6,
  `flush()` antes), senão os textos do mapa apareciam por cima das páginas.
- **Layout**: abas sobem 18 px (o ícone de 16 px não cabia em 14). Duas páginas de até 160×196;
  abaixo de ~300 px de largura de GUI, uma página por vez. O conteúdo é um fluxo: o que não cabe passa
  para a próxima página e, além do par aberto, para outras folhas viradas pelas setas no rodapé.
- **Fechado**: etiqueta de pergaminho no canto superior esquerdo (modo, coordenadas, chunks,
  "desenhando") e um selo "?" com os controles no tooltip; nos primeiros 6 s da primeira abertura
  da sessão os controles aparecem sozinhos num cartão. A faixa preta e a linha de ajuda saíram.
- **Estandartes**: botão direito no mapa cria um rascunho no livro (Salvar/Descartar), que só
  entra no `WaypointStore` ao salvar. Um estandarte existente é editado no lugar: nome e posição
  valem a cada digitação válida (coordenada inválida fica em vermelho) e são gravados ao trocar de
  seleção, de seção, fechar o livro ou sair do mapa. "Centralizar" desliza a vista até ele;
  "Excluir" pede um segundo clique. Com vários mundos do Xaero, "Do Xaero…" abre a lista clássica
  para escolher. `XaeroImport.find()` foi separado em `request()` (estado do cliente, thread
  principal) e `find(Request)` (arquivos, rodando num worker).
- **Animações**: zoom de entrada de 200 ms de 0,85× até um passo inteiro de pixels (o zoom inicial
  é encaixado antes, então não há estalo ao terminar); o livro sobe 10 px e aparece por
  `Spring.SOFT`; as fitas deslizam da borda até o livro; o mouse estica a fita 3 px. A troca de
  seção ficou só com o som de página (`BOOK_PAGE_TURN`), sem o deslizamento de 120 ms previsto.
- **Teclado**: Esc fecha o livro antes do mapa; com um campo focado, as teclas do mapa (C,
  PgUp/PgDn, tecla do mapa, U) não disparam; Espaço aperta o widget focado em vez de recentralizar.
- **Traduções**: `pt_br.json` passou a traduzir todas as chaves do mod (antes cobria só parte).
- **Testes**: JUnit de `SettingsCatalog`, `BookLayout`, `OpeningZoom` e `WaypointDraft`; cenário
  `DIORAMA_TESTS=book-map`. O cenário `waypoints` agora espera o rascunho no livro após o botão
  direito e continua as checagens de campos no `WaypointEditScreen` clássico.

