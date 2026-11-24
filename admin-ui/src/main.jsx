import React, { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { ArrowRight, Bell, Check, Eye, EyeOff, Hash, LockKeyhole, LogOut, Mail, Settings2 } from 'lucide-react'
import './style.css'

const secretKeys = ['gmailClientSecret', 'gmailRefreshToken', 'discordBotToken']

function createDraft(config) {
  return {
    gmailAddress: config.gmailAddress ?? '',
    discordChannelId: config.discordChannelId ?? '',
    enabled: config.enabled ?? false,
    gmailClientId: config.gmailClientId ?? '',
    ...Object.fromEntries(secretKeys.map(key => [key, ''])),
  }
}

function authorization(password) {
  const bytes = new TextEncoder().encode('admin:' + password)
  return 'Basic ' + btoa(String.fromCharCode(...bytes))
}

async function request(auth, draft) {
  const response = await fetch('/api/bot-config', {
    method: draft ? 'PUT' : 'GET',
    credentials: 'omit',
    cache: 'no-store',
    headers: { Authorization: auth, ...(draft && { 'Content-Type': 'application/json' }) },
    body: draft ? JSON.stringify(draft) : undefined,
  })
  if (!response.ok) {
    const details = await response.json().catch(() => null)
    const fallback = {
      400: '입력값을 확인해 주세요.',
      401: '관리자 비밀번호를 확인해 주세요.',
      403: '설정에 접근할 권한이 없습니다.',
    }[response.status] ?? '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
    throw new Error(response.status === 400 && typeof details?.message === 'string' ? details.message : fallback)
  }
  if (response.status === 204) return null
  return response.json()
}

function Brand() {
  return <div className="brand"><span className="brand-icon"><Mail size={20} /></span><span>메일 알림 봇</span></div>
}

function PasswordInput({ id, value, onChange, placeholder, autoComplete, required }) {
  const [visible, setVisible] = useState(false)
  return (
    <div className="password-input">
      <input id={id} type={visible ? 'text' : 'password'} value={value} onChange={onChange}
        placeholder={placeholder} autoComplete={autoComplete} required={required} maxLength={4096} spellCheck={false} />
      <button type="button" onClick={() => setVisible(!visible)} aria-label={visible ? '비밀번호 숨기기' : '비밀번호 표시'}>
        {visible ? <EyeOff size={17} /> : <Eye size={17} />}
      </button>
    </div>
  )
}

function Login({ onLogin }) {
  const [password, setPassword] = useState('')
  const [working, setWorking] = useState(false)
  const [error, setError] = useState('')

  async function submit(event) {
    event.preventDefault()
    setWorking(true)
    setError('')
    try {
      const auth = authorization(password)
      onLogin(auth, await request(auth))
    } catch (err) {
      setError(err.message)
    } finally {
      setWorking(false)
    }
  }

  return (
    <div className="login-page">
      <header><Brand /></header>
      <main className="login-card card">
        <div className="tile"><LockKeyhole size={21} /></div>
        <h1>관리자 로그인</h1>
        <p>Gmail과 디스코드 알림 설정을 관리합니다.</p>
        <form onSubmit={submit}>
          <label htmlFor="admin-password">관리자 비밀번호</label>
          <PasswordInput id="admin-password" required value={password} onChange={event => setPassword(event.target.value)}
            autoComplete="current-password" placeholder="관리자 비밀번호 입력" />
          {error && <p className="error" role="alert">{error}</p>}
          <button type="submit" className="primary" disabled={working}>
            {working ? '로그인 중…' : '로그인'} <ArrowRight size={17} />
          </button>
        </form>
      </main>
    </div>
  )
}

function Section({ icon: Icon, title, hint, children }) {
  return (
    <section className="card settings-card">
      <div className="section-heading">
        <span className="tile"><Icon size={20} /></span>
        <div><h2>{title}</h2><p>{hint}</p></div>
      </div>
      <div className="fields">{children}</div>
    </section>
  )
}

function Field({ id, label, hint, children }) {
  return <div className="field">
    <label htmlFor={id}>{label}</label>
    {children}
    {hint && <p className="field-hint">{hint}</p>}
  </div>
}

function Settings({ auth, initial, onLogout }) {
  const [saved, setSaved] = useState(initial)
  const [draft, setDraft] = useState(() => createDraft(initial))
  const [saving, setSaving] = useState(false)
  const [notice, setNotice] = useState('')
  const [error, setError] = useState('')
  const dirty = Object.keys(draft).some(key => draft[key] !== createDraft(saved)[key])

  function change(key, value) {
    setDraft(previous => ({ ...previous, [key]: value }))
    setNotice('')
    setError('')
  }

  async function submit(event) {
    event.preventDefault()
    if (!dirty) return
    setSaving(true)
    setError('')
    setNotice('')
    const payload = {
      gmailAddress: draft.gmailAddress.trim(),
      gmailClientId: draft.gmailClientId.trim(),
      discordChannelId: draft.discordChannelId.trim(),
      enabled: draft.enabled,
    }
    for (const key of secretKeys) {
      if (draft[key].trim()) payload[key] = draft[key].trim()
    }
    try {
      const config = await request(auth, payload) ?? await request(auth)
      setSaved(config)
      setDraft(createDraft(config))
      setNotice('설정을 저장했습니다. 다음 예약 실행부터 적용됩니다.')
    } catch (err) {
      setError(err.message)
    } finally {
      setSaving(false)
    }
  }

  function secretField(key, label) {
    const configured = saved[key + 'Configured']
    return (
      <Field id={key} label={label} hint={configured ? '등록됨 · 비워 두면 기존 값을 유지합니다.' : '등록 필요'}>
        <PasswordInput id={key} value={draft[key]} onChange={event => change(key, event.target.value)}
          autoComplete="new-password" placeholder={configured ? '새 값을 입력해 변경' : '값 입력'} />
      </Field>
    )
  }

  return (
    <div className="dashboard">
      <aside className="sidebar">
        <Brand />
        <a className="nav-current" href="#settings" aria-current="page"><Settings2 size={18} /> 봇 설정</a>
        <button type="button" className="logout" onClick={onLogout}><LogOut size={17} /> 로그아웃</button>
      </aside>
      <main className="content" id="settings">
        <div className="page-heading">
          <div><h1>봇 설정</h1><p>Gmail 받은편지함을 연결하고 디스코드 알림 채널을 지정합니다.</p></div>
          <div className={'status' + (saved.enabled ? ' active' : '')}><span />{saved.enabled ? '알림 켜짐' : '알림 꺼짐'}</div>
        </div>
        <form onSubmit={submit}>
          <fieldset disabled={saving}>
            <section className="card switch-card">
              <div className="section-heading">
                <span className="tile"><Bell size={20} /></span>
                <div><h2><label htmlFor="enabled">알림 활성화</label></h2><p>예약 실행에서 새 메일을 확인하고 알림을 보냅니다.</p></div>
              </div>
              <input className="switch" id="enabled" type="checkbox" role="switch" checked={draft.enabled}
                onChange={event => change('enabled', event.target.checked)} />
            </section>
            <Section icon={Mail} title="Gmail" hint="메일 주소와 읽기 전용 OAuth 인증 정보를 입력합니다.">
              <Field id="gmailAddress" label="Gmail 주소">
                <input id="gmailAddress" type="email" value={draft.gmailAddress} placeholder="name@gmail.com" maxLength={254}
                  required={draft.enabled} onChange={event => change('gmailAddress', event.target.value)} />
              </Field>
              <Field id="gmailClientId" label="OAuth 클라이언트 ID">
                <input id="gmailClientId" value={draft.gmailClientId} placeholder="…apps.googleusercontent.com" maxLength={512}
                  autoComplete="off" spellCheck={false} onChange={event => change('gmailClientId', event.target.value)} />
              </Field>
              {secretField('gmailClientSecret', 'OAuth 클라이언트 시크릿')}
              {secretField('gmailRefreshToken', 'OAuth 갱신 토큰')}
            </Section>
            <Section icon={Hash} title="디스코드" hint="봇이 메일 알림을 전송할 채널을 설정합니다.">
              <Field id="discordChannelId" label="채널 ID">
                <input id="discordChannelId" value={draft.discordChannelId} placeholder="채널 ID 입력" inputMode="numeric"
                  pattern="[0-9]{17,20}" maxLength={20} required={draft.enabled}
                  onChange={event => change('discordChannelId', event.target.value)} />
              </Field>
              {secretField('discordBotToken', '봇 토큰')}
            </Section>
          </fieldset>
          <div className="form-actions">
            {notice && <p className="success" role="status"><Check size={18} />{notice}</p>}
            {error && <p className="error" role="alert">{error}</p>}
            <button className="primary" type="submit" disabled={saving || !dirty}>{saving ? '저장 중…' : '설정 저장'}</button>
          </div>
        </form>
      </main>
    </div>
  )
}

function App() {
  const [session, setSession] = useState(null)
  return session
    ? <Settings auth={session.auth} initial={session.config} onLogout={() => setSession(null)} />
    : <Login onLogin={(auth, config) => setSession({ auth, config })} />
}

createRoot(document.getElementById('root')).render(<App />)
