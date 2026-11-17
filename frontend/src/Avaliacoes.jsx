import { useEffect, useState } from 'react'
import { ChevronDown, RefreshCw } from 'lucide-react'
import ReactMarkdown from 'react-markdown'
import { listarAvaliacoes } from './services/chat'

const ROTULOS = {
  origem: 'Origem', destino: 'Destino', periodo: 'Período', duracao: 'Duração (dias)', viajantes: 'Viajantes',
  orcamento: 'Orçamento', moeda: 'Moeda', preferencias: 'Preferências', restricoes: 'Restrições',
  coerencia: 'Coerência', completude: 'Completude', transporte: 'Transporte', hospedagem: 'Hospedagem',
  alimentacao: 'Alimentação', passeios: 'Passeios', imprevistos: 'Imprevistos', extrator: 'Extrator',
  inferencia: 'Motor de inferência', judge: 'Judge', contextoRespeitado: 'Contexto', origemRespeitada: 'Origem',
  destinoRespeitado: 'Destino', duracaoRespeitada: 'Duração', viajantesRespeitados: 'Viajantes',
  orcamentoRespeitado: 'Orçamento', periodoRespeitado: 'Período', restricoesRespeitadas: 'Restrições', fontesRespeitadas: 'Fontes',
}

function texto(valor) {
  if (valor == null || valor === '') return 'Não informado'
  if (typeof valor === 'object') return JSON.stringify(valor)
  return String(valor)
}

function moeda(valor, codigo) {
  if (valor == null) return 'Não informado'
  try { return new Intl.NumberFormat('pt-BR', { style: 'currency', currency: codigo || 'BRL' }).format(valor) }
  catch { return `${valor} ${codigo || ''}` }
}

function dataHora(valor) {
  if (valor == null) return 'Não informado'
  // Jackson pode serializar Instant como segundos desde a época Unix.
  const data = new Date(typeof valor === 'number' ? valor * 1000 : valor)
  return Number.isNaN(data.getTime()) ? texto(valor) : data.toLocaleString('pt-BR')
}

function Campos({ dados, formatar = texto }) {
  const entradas = Object.entries(dados || {})
  if (!entradas.length) return <p className="audit-muted">Não registrado.</p>
  return <dl className="audit-fields">{entradas.map(([chave, valor]) => (
    <div key={chave}><dt>{ROTULOS[chave] || chave}</dt><dd>{formatar(valor, chave)}</dd></div>
  ))}</dl>
}

function Secao({ titulo, children }) {
  return <section className="audit-section"><h3>{titulo}</h3>{children}</section>
}

function Bruto({ titulo, valor }) {
  return <details className="audit-raw"><summary>{titulo}</summary><pre>{valor == null ? 'Não registrado.' : typeof valor === 'string' ? valor : JSON.stringify(valor, null, 2)}</pre></details>
}

function Registro({ registro }) {
  const dados = registro.dadosViagem || {}
  const avaliacao = registro.avaliacao || {}
  const nota = avaliacao.nota == null ? 'Sem nota' : `${Number(avaliacao.nota).toFixed(1)}/100`
  const origem = registro.origem === 'CACHE' ? 'Reutilização do cache' : 'Geração de roteiro'
  return <details className="audit-record">
    <summary>
      <div className="audit-record-title">
        <strong>{texto(dados.origem)} → {texto(dados.destino)}</strong>
        <small>{dataHora(registro.dataHora)} · {origem} · Tentativa {registro.tentativa}</small>
        <small>{dados.duracao != null ? `${dados.duracao} dias · ` : ''}{moeda(dados.orcamento, dados.moeda)} · Judge: {texto(registro.configuracaoIa?.judge)}</small>
      </div>
      <span className={`audit-note ${avaliacao.status === 'APROVADO' ? 'approved' : 'rejected'}`}>{nota}</span>
      <ChevronDown className="audit-chevron" size={18} aria-hidden="true" />
    </summary>
    <div className="audit-detail">
      <Secao titulo="Resultado da avaliação">
        <Campos dados={{ status: avaliacao.status, notaMinima: avaliacao.notaMinima }} />
        <h4>Notas por critério</h4>
        <Campos dados={avaliacao.notasCriterios} formatar={(valor, chave) => `${valor}/10${avaliacao.pesos?.[chave] != null ? ` · peso ${avaliacao.pesos[chave]}%` : ''}`} />
        <h4>Indicadores eliminatórios</h4>
        <Campos dados={avaliacao.indicadores} formatar={valor => valor === true ? 'Respeitado' : valor === false ? 'Não respeitado' : texto(valor)} />
        <h4>Motivos</h4>
        {avaliacao.motivos?.length ? <ul>{avaliacao.motivos.map((motivo, i) => <li key={i}>{motivo}</li>)}</ul> : <p className="audit-muted">Nenhum motivo registrado.</p>}
      </Secao>
      <Secao titulo="Dados da viagem"><Campos dados={dados} formatar={(valor, chave) => chave === 'orcamento' ? moeda(valor, dados.moeda) : texto(valor)} /></Secao>
      <Secao titulo="Roteiro avaliado">{registro.roteiro ? <div className="itinerary-text"><ReactMarkdown>{registro.roteiro}</ReactMarkdown></div> : <p className="audit-muted">Não registrado.</p>}</Secao>
      <Secao titulo="Orçamento sugerido"><Campos dados={registro.orcamentoSugerido} formatar={valor => moeda(valor, dados.moeda)} /></Secao>
      <Secao titulo="Processamento">
        <Campos dados={registro.configuracaoIa} />
        <Campos dados={{ 'Tokens de entrada': registro.consumoTokens?.entrada, 'Tokens de saída': registro.consumoTokens?.saida, 'Tokens totais': registro.consumoTokens?.total, 'Tempo (ms)': registro.tempoProcessamentoMs, 'Tentativa': registro.tentativa, 'Origem da avaliação': origem, 'ID da avaliação': registro.id, 'ID do processamento': registro.idProcessamento, 'ID da sessão': registro.idSessao, 'Assinatura do cache': registro.assinaturaCache }} />
      </Secao>
      <Secao titulo="Contexto e respostas originais">
        <Bruto titulo="Histórico da conversa" valor={registro.historico} />
        <Bruto titulo="Fontes consultadas" valor={registro.fontes} />
        <Bruto titulo="Resposta bruta do gerador" valor={registro.respostaBrutaGerador} />
        <Bruto titulo="Respostas brutas do Judge" valor={registro.respostasBrutasJudge} />
      </Secao>
    </div>
  </details>
}

export default function Avaliacoes({ status }) {
  const [registros, setRegistros] = useState([])
  const [carregando, setCarregando] = useState(true)
  const [erro, setErro] = useState('')
  const [versao, setVersao] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    setCarregando(true)
    setErro('')
    listarAvaliacoes(status, controller.signal)
      .then(dados => { if (!controller.signal.aborted) setRegistros(dados) })
      .catch(falha => { if (!controller.signal.aborted) setErro(falha.message) })
      .finally(() => { if (!controller.signal.aborted) setCarregando(false) })
    return () => controller.abort()
  }, [status, versao])

  return <div className="audit-page">
    <div className="audit-list">
      <div className="audit-toolbar">
        <p>{status === 'APROVADO' ? 'Avaliações aprovadas na geração ou reutilização de roteiros.' : 'Tentativas reprovadas pelo Judge ou pelas validações Java.'}</p>
        <button className="topbar-reset" type="button" disabled={carregando} onClick={() => setVersao(v => v + 1)}><RefreshCw size={16} /> Atualizar</button>
      </div>
      {carregando ? <p role="status">Carregando avaliações…</p> : erro ? <div className="error-banner" role="alert">{erro}</div> : registros.length === 0 ? <p className="audit-empty">Nenhuma avaliação encontrada.</p> : registros.map(registro => <Registro key={registro.id} registro={registro} />)}
    </div>
  </div>
}
