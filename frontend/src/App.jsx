import { useEffect, useRef, useState } from 'react'
import {
  ArrowRight, Bot, CalendarDays, Compass, ExternalLink, MapPin, MessageCircle,
  Plus, Send, Settings2, Sparkles, Users, Wallet, X, CheckCircle, XCircle,
} from 'lucide-react'
import { enviarMensagem } from './services/chat'
import ReactMarkdown from 'react-markdown'
import Avaliacoes from './Avaliacoes'

const STORAGE_KEY = 'viajar-conversa-v1'
const CONFIGURACAO_PADRAO = { extrator: 'GEMINI', inferencia: 'GEMINI', judge: 'GEMINI' }
const ETAPAS_IA = [
  { chave: 'extrator', rotulo: 'Extrator de dados' },
  { chave: 'inferencia', rotulo: 'Motor de inferência' },
  { chave: 'judge', rotulo: 'LLM-as-a-Judge' },
]

// Recupera apenas provedores reconhecidos pelo backend.
function carregarConfiguracao(configuracao) {
  return Object.fromEntries(ETAPAS_IA.map(({ chave }) => [
    chave, configuracao?.[chave] === 'OLLAMA' ? 'OLLAMA' : CONFIGURACAO_PADRAO[chave],
  ]))
}
const SUGESTOES = [
  'Quero planejar uma viagem saindo de Salvador',
  'Gosto de natureza e quero descansar',
  'Quero conhecer Recife em janeiro de 2027',
]
const CATEGORIAS = {
  transporte: 'Transporte',
  hospedagem: 'Hospedagem',
  alimentacao: 'Alimentação',
  passeios: 'Passeios',
  imprevistos: 'Imprevistos',
}

function carregarConversa() {
  try {
    const saved = JSON.parse(sessionStorage.getItem(STORAGE_KEY) || '{}')
    return {
      sessao: typeof saved.sessao === 'string' ? saved.sessao : '',
      mensagens: Array.isArray(saved.mensagens) ? saved.mensagens : [],
      dados: saved.dados && typeof saved.dados === 'object' ? saved.dados : {},
      configuracaoIa: carregarConfiguracao(saved.configuracaoIa),
    }
  } catch {
    return { sessao: '', mensagens: [], dados: {}, configuracaoIa: carregarConfiguracao() }
  }
}

function moeda(valor, codigo = 'BRL') {
  const numero = Number(valor)
  if (!Number.isFinite(numero)) return String(valor)
  try {
    return new Intl.NumberFormat('pt-BR', { style: 'currency', currency: codigo || 'BRL' }).format(numero)
  } catch {
    return `${numero.toLocaleString('pt-BR', { minimumFractionDigits: 2 })} ${codigo || 'BRL'}`
  }
}

function texto(valor) {
  if (Array.isArray(valor)) return valor.join(', ')
  return valor == null ? '' : String(valor)
}

function Resposta({ item }) {
  const retorno = item.retorno
  const valores = retorno.orcamentoSugerido ? Object.entries(retorno.orcamentoSugerido) : []
  const fontes = Array.isArray(retorno.fontes) ? retorno.fontes : []
  const tokens = retorno.consumoTokens

  return (
    <div className="assistant-content">
      <div className="message-text">
        <ReactMarkdown>{retorno.resposta}</ReactMarkdown>
      </div>

      {retorno.roteiro && (
        <section className="answer-section" aria-label="Roteiro da viagem">
          <div className="section-heading"><Compass size={17} /><h3>Seu roteiro</h3></div>
          <div className="itinerary-text">
            <ReactMarkdown>{retorno.roteiro}</ReactMarkdown>
          </div>
        </section>
      )}

      {valores.length > 0 && (
        <section className="answer-section" aria-label="Distribuição sugerida do orçamento">
          <div className="section-heading"><Wallet size={17} /><h3>Orçamento sugerido</h3></div>
          <div className="budget-grid">
            {valores.map(([categoria, valor]) => (
              <div className="budget-item" key={categoria}>
                <span>{CATEGORIAS[categoria] || categoria}</span>
                <strong>{moeda(valor, retorno.dados?.moeda)}</strong>
              </div>
            ))}
          </div>
          <p className="section-note">Alocações para a viagem inteira. Não são preços ou cotações.</p>
        </section>
      )}

      {fontes.length > 0 && (
        <section className="answer-section" aria-label="Fontes consultadas">
          <div className="section-heading"><ExternalLink size={17} /><h3>Fontes consultadas</h3></div>
          {fontes.map((fonte) => (
            <a className="source-link" href={fonte.url} key={fonte.id} target="_blank" rel="noreferrer">
              <span><strong>{fonte.nome}</strong> <small>{fonte.id} · {fonte.provedor}</small></span>
              <ExternalLink size={15} aria-hidden="true" />
            </a>
          ))}
        </section>
      )}

      <div className="answer-meta">
        <span className="origin-badge">
          {retorno.origemResposta === 'BASE_CONHECIMENTO'
              ? 'Base de conhecimento'
              : retorno.origemResposta === 'CACHE'
                  ? 'Plano reutilizado'
                  : 'Via.jar com IA'}
        </span>
        {(retorno.cache === 'HIT' || retorno.cache === 'MISS') && <span>Cache {retorno.cache}</span>}
        {tokens?.total != null && <span>{tokens.total} tokens</span>}
        {retorno.notaAvaliacao != null && <span>Judge: {retorno.notaAvaliacao.toFixed(1)}/100</span>}
      </div>
    </div>
  )
}

export default function App() {
  const [inicial] = useState(carregarConversa)
  const [sessao, setSessao] = useState(inicial.sessao)
  const [mensagens, setMensagens] = useState(inicial.mensagens)
  const [dados, setDados] = useState(inicial.dados)
  const [configuracaoIa, setConfiguracaoIa] = useState(inicial.configuracaoIa)
  const [rascunho, setRascunho] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [erro, setErro] = useState('')
  const [menuAberto, setMenuAberto] = useState(false)
  const [pagina, setPagina] = useState('CHAT')
  const fim = useRef(null)
  const campo = useRef(null)

  useEffect(() => {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ sessao, mensagens, dados, configuracaoIa }))
  }, [sessao, mensagens, dados, configuracaoIa])

  // Rola automaticamente até a última interação.
  useEffect(() => {
    fim.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }, [mensagens, enviando, pagina])

  // Devolve o foco ao campo de mensagem após o processamento.
  useEffect(() => {
    if (!enviando && pagina === 'CHAT') {
      campo.current?.focus({ preventScroll: true })
    }
  }, [enviando, pagina])

  async function enviar(valor = rascunho) {
    const mensagem = valor.trim()
    if (!mensagem || enviando) return
    setErro('')
    setRascunho('')
    setEnviando(true)
    setMensagens((lista) => [...lista, { id: crypto.randomUUID(), papel: 'usuario', texto: mensagem }])
    try {
      const retorno = await enviarMensagem(mensagem, sessao, configuracaoIa)
      setSessao(retorno.sessao)
      setDados(retorno.dados || {})
      setMensagens((lista) => [...lista, { id: crypto.randomUUID(), papel: 'assistente', retorno }])
    } catch (falha) {
      setErro(falha.message)
    } finally {
      setEnviando(false)
    }
  }

  function novaConversa() {
    if (enviando) return
    setPagina('CHAT')
    setSessao('')
    setMensagens([])
    setDados({})
    setRascunho('')
    setErro('')
    setMenuAberto(false)
    campo.current?.focus()
  }

  function navegar(destino) {
    setPagina(destino)
    setMenuAberto(false)
  }

  const resumo = [
    { icon: MapPin, label: 'Origem', value: dados.origem },
    { icon: Compass, label: 'Destino', value: dados.destino },
    { icon: CalendarDays, label: 'Período', value: dados.periodo },
    { icon: Users, label: 'Viajantes', value: dados.viajantes },
    { icon: Wallet, label: 'Orçamento', value: dados.orcamento != null ? moeda(dados.orcamento, dados.moeda) : '' },
  ]

  return (
    <div className="app-shell">
      {menuAberto && <button className="mobile-overlay" type="button" aria-label="Fechar menu" onClick={() => setMenuAberto(false)} />}
      <aside className={`sidebar ${menuAberto ? 'sidebar-open' : ''}`}>
        <div className="brand"><span className="brand-mark"><Compass size={24} strokeWidth={2.2} /></span><span><strong>Via.jar</strong><small>Seu próximo destino começa aqui</small></span></div>
        <button className="new-chat" type="button" onClick={novaConversa}><Plus size={18} /> Nova conversa</button>
        <nav className="audit-nav" aria-label="Navegação principal">
          {[{ id: 'CHAT', label: 'Chat', Icon: MessageCircle },
            { id: 'APROVADO', label: 'Roteiros aprovados', Icon: CheckCircle },
            { id: 'REPROVADO', label: 'Roteiros reprovados', Icon: XCircle }].map(({ id, label, Icon }) => (
            <button key={id} type="button" className={pagina === id ? 'active' : ''}
              aria-current={pagina === id ? 'page' : undefined} onClick={() => navegar(id)}><Icon size={17} />{label}</button>
          ))}
        </nav>
        <section className="sidebar-section ai-section" aria-labelledby="ai-title">
          <div className="ai-heading"><Settings2 size={16} aria-hidden="true" /><span id="ai-title" className="sidebar-label">Configuração de IA</span></div>
          <div className="ai-config">
            {ETAPAS_IA.map(({ chave, rotulo }) => (
              <label className="ai-field" key={chave} htmlFor={`ia-${chave}`}>
                <span>{rotulo}</span>
                <select id={`ia-${chave}`} value={configuracaoIa[chave]} disabled={enviando}
                  onChange={(evento) => setConfiguracaoIa((atual) => ({ ...atual, [chave]: evento.target.value }))}>
                  <option value="GEMINI">Gemini · Cloud</option>
                  <option value="OLLAMA">Ollama · Local</option>
                </select>
              </label>
            ))}
          </div>
          <p className="summary-hint">As escolhas serão utilizadas na próxima mensagem.</p>
        </section>
        <div className="sidebar-section">
          <span className="sidebar-label">Sua viagem</span>
          <div className="trip-summary">
            {resumo.map(({ icon: Icon, label, value }) => (
              <div className="summary-row" key={label}>
                <Icon size={17} aria-hidden="true" />
                <span><small>{label}</small><strong>{texto(value) || 'A definir'}</strong></span>
              </div>
            ))}
          </div>
          <p className="summary-hint">Conte seus planos aos poucos. Você pode corrigir qualquer informação na conversa.</p>
        </div>
        <div className="sidebar-bottom"><span className="online-dot" /> Planejamento personalizado com IA</div>
      </aside>

      <main className="main-panel">
        <header className="topbar">
          <button className="mobile-menu" type="button" aria-label="Abrir menu" onClick={() => setMenuAberto(true)}><Compass size={23} /></button>
          <div className="topbar-title"><span className="topbar-icon"><Sparkles size={18} /></span><div><strong>{pagina === 'CHAT' ? 'Planeje sua viagem' : pagina === 'APROVADO' ? 'Roteiros aprovados' : 'Roteiros reprovados'}</strong><small>{pagina === 'CHAT' ? 'Converse com o Via.jar' : 'Histórico de avaliações'}</small></div></div>
          <button className="topbar-reset" type="button" onClick={novaConversa} title="Nova conversa"><Plus size={17} /><span>Nova conversa</span></button>
        </header>

        {pagina === 'CHAT' ? (<>
        <div className="conversation" aria-live="polite">
          {mensagens.length === 0 ? (
            <div className="welcome">
              <div className="welcome-symbol"><Compass size={38} strokeWidth={1.6} /></div>
              <span className="eyebrow">VIA.JAR · ASSISTENTE DE VIAGENS</span>
              <h1>Para onde vamos?</h1>
              <p>Conte sua origem, orçamento, período ou o tipo de viagem que deseja. Vamos montar um plano no seu ritmo.</p>
              <div className="suggestions">
                {SUGESTOES.map((sugestao) => <button type="button" key={sugestao} onClick={() => enviar(sugestao)}>{sugestao}<ArrowRight size={16} /></button>)}
              </div>
            </div>
          ) : (
            <div className="message-list">
              {mensagens.map((item) => (
                <div className={`message-row ${item.papel === 'usuario' ? 'user-row' : ''}`} key={item.id}>
                  <div className={`avatar ${item.papel === 'usuario' ? 'user-avatar' : ''}`}>{item.papel === 'usuario' ? <MessageCircle size={19} /> : <Bot size={20} />}</div>
                  <div className={`message-card ${item.papel === 'usuario' ? 'user-card' : ''}`}>
                    {item.papel === 'usuario' ? <p className="message-text">{item.texto}</p> : <Resposta item={item} />}
                  </div>
                </div>
              ))}
              {enviando && <div className="message-row"><div className="avatar"><Bot size={20} /></div><div className="message-card thinking"><span /><span /><span /><em>Preparando a resposta…</em></div></div>}
              <div ref={fim} />
            </div>
          )}
        </div>

        <div className="composer-wrap">
          {erro && <div className="error-banner" role="alert"><span>{erro}</span><button type="button" onClick={() => setErro('')} aria-label="Dispensar erro"><X size={16} /></button></div>}
          <form className="composer" onSubmit={(evento) => { evento.preventDefault(); enviar() }}>
            <label className="sr-only" htmlFor="mensagem">Sua mensagem</label>
            <textarea id="mensagem" ref={campo} value={rascunho} onChange={(evento) => setRascunho(evento.target.value)} onKeyDown={(evento) => { if (evento.key === 'Enter' && !evento.shiftKey) { evento.preventDefault(); enviar() } }} placeholder="Ex.: Quero viajar de Salvador para Recife em janeiro de 2027…" rows={2} disabled={enviando} />
            <button className="send-button" type="submit" disabled={!rascunho.trim() || enviando} aria-label="Enviar mensagem"><Send size={19} /></button>
          </form>
          <p className="composer-note">O orçamento é uma sugestão de distribuição, não uma cotação de preços.</p>
        </div>
        </>) : <Avaliacoes key={pagina} status={pagina} />}
      </main>
    </div>
  )
}
