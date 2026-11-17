# Frontend Via.jar

Interface React para o chatbot de viagens do Via.jar. Usa o endpoint `POST /api/chat` da aplicação Java; não contém respostas simuladas.

## Executar com Docker Compose

Inicie o Java pelo IntelliJ na porta 8080. Depois, na pasta `via-jar`:

```powershell
docker compose up --build -d
```

Abra `http://localhost:5173`. O Compose atual inicia somente o frontend; o Java continua no IntelliJ. O proxy `/api` aponta para `host.docker.internal:8080`. Os arquivos `src`, `public`, `index.html` e `vite.config.js` são montados no contêiner, e o Vite atualiza a página automaticamente quando você os edita. Alterações em `package.json` ou `package-lock.json` exigem executar novamente `docker compose up --build -d` para reinstalar dependências na imagem. Para parar: `docker compose down`.

Esta configuração usa o servidor de desenvolvimento Vite e é destinada a desenvolvimento e demonstração em uma rede confiável. Se a porta 5173 estiver ocupada, defina `VIAJAR_FRONTEND_PORT=5174` no `.env` da pasta `via-jar` e acesse `http://localhost:5174`.

## Executar sem Docker

Inicie o backend na porta 8080 seguindo o [README do Via.jar](../via-jar/README.md). Depois, nesta pasta:

```powershell
npm install
npm run dev
```

Abra `http://localhost:5173`. O Vite encaminha `/api` ao backend. Se o Java estiver em outra porta, crie um `.env` nesta pasta com `VIAJAR_BACKEND_URL=http://localhost:8082` e reinicie o Vite. A chave Gemini fica somente no backend; não coloque `GOOGLE_API_KEY` no frontend.

Para conferir a compilação: `npm run build`. Para verificar o código: `npm run lint`.

## Conversa

Escreva seus dados de viagem aos poucos. A interface envia o identificador `sessao` recebido ao backend nas mensagens seguintes. A conversa e o resumo da viagem ficam no `sessionStorage` da aba; **Nova conversa** inicia outra sessão. O backend mantém o contexto temporariamente, então uma sessão antiga pode expirar.

Quando o backend retornar um plano, a tela mostra separadamente a mensagem, o roteiro, a alocação do orçamento e as fontes Wikidata utilizadas. Também mostra a origem `COLETA_DADOS`, `BASE_CONHECIMENTO`, `CACHE` ou `LLM`, o status do cache e os tokens informados pelo provedor. Ao informar origem e destino, o Via.jar pode oferecer até três roteiros salvos, mesmo sem os outros dados. Responda `sim` para a opção única, informe o número quando houver várias ou diga `não` para continuar a coleta. Se seus dados diferirem dos salvos, a oferta mostra as diferenças e pede confirmação. A alocação é uma sugestão, não uma cotação.

O build estático usa `/api/chat` na mesma origem. Para publicá-lo em outra origem, configure um proxy reverso para o backend ou habilite CORS nele e defina `VITE_API_URL` antes de compilar.
