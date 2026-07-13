// [AGENT] 백엔드 패턴 카탈로그 모달 — 왼쪽 패턴 목록, 오른쪽 3단 탭(흐름 | 뼈대 코드 | 실물).
// 원장은 ../backendPatterns.js (사람용 교보재). 에이전트용 인덱스는 springboot/AGENTS.md 표.
import { useState } from 'react';
import shared from '../AdminPage.module.css';
import s from './BackendPatternModal.module.css';
import { BACKEND_PATTERNS, BACKEND_PATTERN_GROUPS } from '../backendPatterns';

const TABS = ['흐름', '뼈대 코드', '실물'];

export default function BackendPatternModal({ open, onClose }) {
    const [selectedKey, setSelectedKey] = useState(BACKEND_PATTERNS[0]?.key ?? null);
    const [tab, setTab] = useState(TABS[0]);
    if (!open) return null;
    const selected = BACKEND_PATTERNS.find(pattern => pattern.key === selectedKey) ?? BACKEND_PATTERNS[0];
    return (
        <div className={s.overlay} onClick={onClose}>
            <div className={s.modal} onClick={(event) => event.stopPropagation()}>
                <div className={shared.titleRow}>
                    <div>
                        <div className={shared.title}>백엔드 패턴 카탈로그 ({BACKEND_PATTERNS.length})</div>
                        <div className={shared.subtitle}>키 이름으로 지시 가능 — "이건 pattern-queue-worker 로"</div>
                    </div>
                    <button type="button" className={shared.btn} onClick={onClose}>
                        닫기
                    </button>
                </div>
                <div className={s.body}>
                    <nav className={s.list}>
                        {BACKEND_PATTERN_GROUPS.map(group => {
                            const patterns = BACKEND_PATTERNS.filter(pattern => pattern.group === group);
                            if (patterns.length === 0) return null;
                            return (
                                <div key={group}>
                                    <div className={s.listGroup}>{group}</div>
                                    {patterns.map(pattern => (
                                        <button
                                            key={pattern.key}
                                            type="button"
                                            className={`${s.listItem} ${pattern.key === selected.key ? s.listItemSelected : ''}`}
                                            onClick={() => setSelectedKey(pattern.key)}
                                        >
                                            <span className={s.listKey}>{pattern.key.replace(/^pattern-/, '')}</span>
                                            <span className={s.listLabel}>{pattern.label}</span>
                                        </button>
                                    ))}
                                </div>
                            );
                        })}
                    </nav>
                    <div className={s.detail}>
                        <div className={s.detailHead}>
                            <code className={s.detailKey}>{selected.key}</code>
                            <span className={s.detailLabel}>{selected.label}</span>
                        </div>
                        <div className={shared.desc}>{selected.intent}</div>
                        <div className={s.when}>언제 쓰나 — {selected.when}</div>
                        <div className={s.tabs} role="tablist">
                            {TABS.map(name => (
                                <button
                                    key={name}
                                    type="button"
                                    role="tab"
                                    aria-selected={tab === name}
                                    className={`${s.tab} ${tab === name ? s.tabSelected : ''}`}
                                    onClick={() => setTab(name)}
                                >
                                    {name}
                                </button>
                            ))}
                        </div>
                        {tab === '흐름' && (
                            <ol className={s.flow}>
                                {selected.flow.map(step => (
                                    <li key={step} className={s.flowStep}>{step}</li>
                                ))}
                            </ol>
                        )}
                        {tab === '뼈대 코드' && (
                            <pre className={s.codeBlock}><code>{selected.skeleton}</code></pre>
                        )}
                        {tab === '실물' && (
                            <ul className={s.examples}>
                                {selected.examples.map(path => (
                                    <li key={path}><code className={s.examplePath}>{path}</code></li>
                                ))}
                                <li className={s.exampleHint}>뼈대는 증류본 — 실제 코딩 전에는 실물을 열어 그대로 모방합니다.</li>
                            </ul>
                        )}
                    </div>
                </div>
            </div>
        </div>
    );
}
