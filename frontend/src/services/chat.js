const URL_BASE = (import.meta.env.VITE_API_URL || '').replace(/\/$/, '')

// Envia a mensagem e a configuração de IA ao backend.
export async function enviarMensagem(mensagem, sessao, configuracaoIa) {
    let resposta

    try {
        resposta = await fetch(`${URL_BASE}/api/chat`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                mensagem,
                ...(sessao ? {sessao} : {}),
                ...(configuracaoIa ? {configuracaoIa} : {})
            }),
        })
    } catch {
        throw new Error('Não foi possível conectar ao Via.jar. Confira se a aplicação Java está iniciada.')
    }

    const corpo = await resposta.json().catch(() => null)

    if (!resposta.ok) {
        console.error('Erro na API:', {
            status: resposta.status,
            corpo: corpo
        })

        throw new Error(corpo?.erro || 'O Via.jar não conseguiu processar a mensagem. Tente novamente.')
    }

    if (!corpo || typeof corpo.resposta !== 'string' || typeof corpo.sessao !== 'string') {
        throw new Error('O Via.jar retornou uma resposta inesperada.')
    }

    return corpo
}

// Consulta o histórico de avaliações do Judge.
export async function listarAvaliacoes(status, signal) {
    const parametros = new URLSearchParams({status})
    const resposta = await fetch(`${URL_BASE}/api/avaliacoes?${parametros}`, {signal})
    const corpo = await resposta.json().catch(() => null)

    if (!resposta.ok) {
        console.error('Erro ao consultar avaliações:', {
            status: resposta.status,
            corpo: corpo
        })

        throw new Error(corpo?.erro || 'Não foi possível consultar as avaliações.')
    }

    if (!Array.isArray(corpo)) {
        throw new Error('O Via.jar retornou uma listagem inesperada.')
    }

    return corpo
}