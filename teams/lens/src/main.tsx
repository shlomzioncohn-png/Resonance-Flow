import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './styles.css'

function App() {
  return (
    <main className="app-shell">
      <p className="eyebrow">Resonance Flow</p>
      <h1>LENS is running</h1>
      <p className="status">Frontend foundation is ready for the next slice.</p>
    </main>
  )
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
