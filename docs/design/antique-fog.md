# Fog de mapa antigo e prévia regional por seed

Status: plano técnico para revisão, sem implementar o novo estilo nesta etapa.
Primeiro alvo: Fabric 26.3. O fog atual continua disponível e selecionado nas
configurações existentes. Este plano não muda a exploração salva.

## Resultado visual

Adicionar `Estilo do fog: Nuvens / Mapa antigo`, compartilhado entre o minimapa,
o mapa mundial e o Mod Menu. A seleção altera apenas a composição visual.

O estilo Mapa antigo usa uma textura real e pequena de pergaminho: fibras suaves,
manchas discretas, sépia quente e tinta marrom. O padrão fica ancorado nas
coordenadas do mapa para não deslizar ao mover a câmera. Uma segunda escala do
mesmo padrão disfarça a repetição, sem várias camadas animadas de ruído.

As bordas das costas e os símbolos usam degraus, pixels e pequenos volumes cúbicos
compatíveis com Minecraft. Evitar linhas vetoriais excessivamente lisas, fotos de
terreno e contornos de chunks retangulares visíveis. A textura do papel não deve
reduzir a legibilidade dos marcadores.

Áreas desconhecidas mostram papel e, opcionalmente, uma cartografia esmaecida.
Ao explorar, a máscara existente revela o mapa real em cores, com os blocos,
construções e luzes efetivamente observados. A transição é uma borda irregular
estreita, sem partículas ou animação contínua. O papel não tinge o terreno revelado.

## O que a seed permite

Não há uma imagem completa e gratuita do mundo armazenada na seed. Ela é uma
entrada para o gerador. Gerar chunks antecipadamente, simular blocos, estruturas,
árvores ou iluminação para desenhar o fog contraria o objetivo de baixo custo.

A opção recomendada consulta somente a distribuição de biomas em uma grade
esparsa. Mostra regiões aproximadas de oceano, floresta, deserto e montanha como
cartografia. Bioma não é altura nem litoral exato: a prévia não promete praias,
rios ou relevo preciso, especialmente em presets e geradores personalizados.

Na cópia local de Minecraft 26.3 foram verificados `BiomeSource`, `RandomState`
e `NoiseBasedChunkGenerator.getBaseHeight`. A primeira implementação NÃO chama
`getBaseHeight`: essa consulta também avalia geração de terreno e precisa de um
experimento de desempenho separado antes de sequer considerar alturas aproximadas.

No singleplayer, o servidor integrado fornece a seed e os registros do gerador
em uso. Hoje `MapSession.slimeSeed()` já obtém a seed do Overworld, mas não basta
reutilizar esse número para outras dimensões ou geradores. Capturar uma descrição
imutável do gerador e criar um amostrador exclusivo para o worker. Não compartilhar
caches mutáveis do gerador ativo nem consultar o servidor por amostra.

Em multiplayer, `WorldKey` usa uma seed com hash para identificar o mundo; esse
valor NÃO é a seed original e não permite reproduzir a geração. Sem seed original
e configuração compatível, o estilo continua funcionando com papel, sem a prévia.
Suporte a dados fornecidos pelo servidor é uma extensão futura. Restrições do
servidor continuam prevalecendo; não inferir seed nem revelar cavernas, estruturas,
minérios, construções ou entidades por essa camada.

## Estratégia de custo mínimo

1. Só pedir dados da região atualmente visível, com uma pequena margem. Nunca
   preencher o mundo inteiro, um raio ilimitado ou toda a área explorada.
2. Gerar primeiro uma grade de 16×16 amostras cobrindo uma região de 4096×4096
   blocos (passo 256). Refinar até 64×64 (passo 64) somente onde o zoom realmente
   acrescenta detalhe. Reutilizar as amostras grossas no refinamento.
3. Trabalhar com índices de biomas agrupados em poucas classes visuais. Ícones de
   árvores e montanhas descrevem a classe; não representam objetos reais gerados.
4. Um worker de baixa prioridade, fila de até duas regiões e cancelamento por
   revisão do mundo e do viewport. Esperar cerca de 250 ms após um movimento
   contínuo antes de iniciar uma nova região distante.
5. Amostragem em fatias cooperativas com alvo inicial de até 2 ms por fatia e
   20 ms de CPU por segundo. Isso é um orçamento a medir, não uma garantia de
   que cada chamada ao gerador termine nesse prazo. Verificar o tempo após cada
   amostra e adaptar quantidade/pausas; um gerador com chamadas individuais caras
   perde o refinamento ou desabilita a prévia. Cancelar antes da próxima amostra.
6. Nenhuma consulta ao gerador, espera por futuro, leitura de disco ou criação de
   chunks na thread de renderização. Em falta de dados, desenhar só o pergaminho.
7. Cache de prévias separado: até 4 MiB de CPU, 8 MiB de GPU e 64 MiB em disco,
   todos com LRU. Os 8 MiB de GPU incluem o papel, símbolos e buffers de upload;
   não são apenas o limite dos tiles de prévia. Contabilizar também o amostrador,
   filas e buffers no alvo de até 32 MiB de RAM adicional total. Validar o contexto
   do gerador contra esse alvo antes de habilitar a prévia. Textura compartilhada
   de papel até 512×512 RGBA, atlas pequeno de símbolos e um único passe de
   composição. O estilo fica estático quando a câmera e a exploração não mudam.
8. Chave de cache: identidade do mundo, dimensão, fingerprint da seed e da
   configuração/registros/datapacks do gerador, versão do Minecraft, região,
   resolução e versão do algoritmo. Calcular fingerprints uma vez na entrada do
   mundo; invalidar somente a prévia quando o gerador mudar.

Uma prévia grossa aparece primeiro e pode ganhar detalhes depois. Esse refinamento
decorativo não altera a exploração. Se CPU, memória ou fila excederem o orçamento,
priorizar o papel e descartar trabalho de regiões que saíram da tela.

Também foi considerada a biblioteca nativa [Cubiomes](https://github.com/Cubitect/cubiomes),
que oferece geração regional de biomas em várias escalas. Ela merece um benchmark
comparativo se a consulta vanilla se tornar o gargalo, mas não é uma dependência
automática: exige integração e distribuição nativas, não acompanha geradores de mods
e o `MCVersion` da árvore consultada não declara Minecraft 26.3. Primeiro otimizar
quantas amostras são necessárias e reutilizá-las; só trocar de implementação com
ganho medido e compatibilidade comprovada.

## Integração proposta

| Parte | Responsabilidade |
| --- | --- |
| `Settings.FogStyle` | Seleção Nuvens/Mapa antigo; sem chamar `MapLevel.colorsChanged` |
| `MapViewport` | Reutilizar a máscara de cobertura; compor apenas onde ela oculta terreno |
| `AntiqueFogPass` / texturas compartilhadas | Papel, tinta e borda cúbica em um passe |
| `SeedPreviewSource` | Adaptador opcional de biomas; captura imutável e amostrador próprio |
| `PreviewScheduler` / `PreviewCache` | Orçamentos, prioridade, cancelamento e LRU separados |

A prévia nunca chama `markSeen`, nunca incrementa a cobertura e nunca grava tiles
reais `.omt`/`.omt2`. Explorar tem prioridade; seu mapa real substitui a prévia sem
depender de ela estar pronta. Cavernas e dimensões sem um adaptador validado recebem
apenas papel. A orientação usa a mesma `Projection` das quatro direções cardinais.

## Relação com a correção de aparência

O bug de atualização parcial vem de imagens com outline e shading já gravados:
trocar cada resultado individualmente deixa uma mistura de aparências. A correção
prepara uma revisão e publica o conjunto de tiles residentes de uma vez. Mantém
as texturas e a cobertura anteriores até o novo conjunto ficar pronto. Overviews
precisam ser derivados após os tiles detalhados, e não recarregados de dados antigos.

Isso não recupera informações que arquivos antigos não guardam. Regiões cujo
terreno não está nos snapshots conservam pixels salvos; não é possível remover
com precisão uma sombra ou um contorno desses pixels, nem recuperar luz de tochas
de um tile legado apenas diurno. Não apagar mapa nem tratar uma aproximação como
restyling exato. Uma futura evolução deve persistir atributos de aparência
separados da cor final para permitir essas opções diretamente no render, com
formato, mipmaps, custo e migração incremental próprios.

O novo fog já deve nascer independente dessa limitação: papel, sépia e prévia são
camadas de composição separadas, e trocar seu estilo afeta o viewport inteiro no
próximo frame, sem reprocessar o mapa.

## Entrega e critérios de aceitação

1. **Estilo visual sem seed:** validar pergaminho, borda, cores, quatro direções,
   zoom, minimapa, Mod Menu e recarga de recursos. O modo Nuvens permanece intacto.
2. **Experimento de biomas:** medir o adaptador vanilla do singleplayer, em
   máquina real, sem desenhar terreno extra. Se o orçamento não for viável,
   manter o estilo completo sem seed e registrar o resultado.
3. **Prévia opcional:** habilitar após validar cancelamento, cache, migração entre
   mundos e ausência de impacto na exploração. Primeiro Overworld vanilla;
   outros geradores precisam de adaptadores e testes próprios.

Portas de desempenho, a medir contra o mesmo mundo sem o novo fog:

- Zero chunks solicitados, gerados ou carregados exclusivamente para a prévia.
- Zero jobs de terrain bake, snapshots e gravações de tiles reais ao trocar
  Nuvens/Mapa antigo; zero amostras novas em um viewport parado com cache pronto.
- Alvo de acréscimo p95 abaixo de 0,3 ms por frame no render; até um upload de
  prévia de no máximo 64 KiB por frame, limitado também por bytes.
- CPU/GPU/disco dentro dos tetos acima em um percurso longo e ao panear rapidamente.
- Verificar separadamente custo médio e p95 por amostra; pior chamada de geradores
  personalizados não pode ser escondida pela média.
- Abrir o mesmo lugar com seed ausente, cache corrompido ou após trocar datapack:
  continua havendo papel, sem erro e sem apagar o mapa real.
- Um mundo 100% desconhecido continua 0% explorado após gerar a prévia completa
  do viewport. Casas e outras alterações do jogador só aparecem no mapa real.
- Em mundos atualizados de outra versão ou customizados, a prévia pode discordar
  do terreno. A exploração observada sempre vence; não adaptar o mapa real à seed.

## Fontes verificadas

- Código local: `MapSession.slimeSeed`, `WorldKey`, `MapViewport`, `TileStore`
  e `TileBaker`, além das fontes de Minecraft 26.3 no cache de desenvolvimento.
- [Fabric: Dimension Concepts](https://wiki.fabricmc.net/tutorial:dimensionconcepts):
  distinção entre gerador, fonte de biomas e regras de superfície. O documento é
  conceitual; os nomes concretos de 26.3 foram conferidos nas fontes locais.
- [Cubiomes: gerador regional](https://github.com/Cubitect/cubiomes#biome-generation-in-a-range)
  e [versões declaradas](https://github.com/Cubitect/cubiomes/blob/master/biomes.h):
  avaliação da alternativa nativa, sem pressupor suporte à versão atual do projeto.
