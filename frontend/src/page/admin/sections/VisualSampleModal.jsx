// [AGENT] UI 샘플 카탈로그 — 조밀 타일로 한 화면에서 전체 열람, 탭하면 하단 상세줄에 의도·사용 코드.
// 원장은 shared/ui/samples/visualSamples.js (group·shape 필드). 1회성(oneShot) 효과는 여기서만 무한 반복.
import { useState } from 'react';
import shared from '../AdminPage.module.css';
import s from './VisualSampleModal.module.css';
import { VISUAL_EFFECT_SAMPLES, VISUAL_SAMPLE_GROUPS } from '@/shared/ui/samples/visualSamples';

export default function VisualSampleModal({ open, onClose }) {
    const [selectedKey, setSelectedKey] = useState(null);
    if (!open) return null;
    const selected = VISUAL_EFFECT_SAMPLES.find(sample => sample.key === selectedKey) ?? null;
    return (
        <div className={s.overlay} onClick={onClose}>
            <div className={s.modal} onClick={(event) => event.stopPropagation()}>
                <div className={shared.titleRow}>
                    <div>
                        <div className={shared.title}>UI 샘플 카탈로그 ({VISUAL_EFFECT_SAMPLES.length})</div>
                        <div className={shared.subtitle}>타일을 누르면 사용 코드가 아래에 — class name으로 재사용</div>
                    </div>
                    <button
                        type="button"
                        className={shared.btn}
                        onClick={onClose}
                    >
                        닫기
                    </button>
                </div>
                <div className={s.groups}>
                    {VISUAL_SAMPLE_GROUPS.map(group => {
                        const samples = VISUAL_EFFECT_SAMPLES.filter(sample => sample.group === group);
                        if (samples.length === 0) return null;
                        return (
                            <section key={group} className={s.group}>
                                <h3 className={s.groupTitle}>{group} <span className={s.groupCount}>{samples.length}</span></h3>
                                <div className={s.tiles}>
                                    {samples.map(sample => (
                                        <button
                                            key={sample.key}
                                            type="button"
                                            className={`${s.tile} ${sample.key === selectedKey ? s.tileSelected : ''}`}
                                            onClick={() => setSelectedKey(sample.key)}
                                        >
                                            <span className={`${s.preview} ${sample.oneShot ? s.loop : ''}`}>
                                                <span
                                                    className={`${s.glyph} ${s[`shape_${sample.shape}`] ?? ''} ${sample.className}`}
                                                    aria-hidden="true"
                                                />
                                            </span>
                                            {/* 좁은 타일에 맞춰 공통 접두사(sample_)는 생략 표기 */}
                                            <span className={s.tileKey}>{sample.key.replace(/^sample_/, '')}</span>
                                        </button>
                                    ))}
                                </div>
                            </section>
                        );
                    })}
                </div>
                <div className={s.detail}>
                    {selected ? (
                        <>
                            <div className={s.detailHead}>
                                <code className={s.detailKey}>{selected.key}</code>
                                <span className={s.detailLabel}>{selected.label}</span>
                                {selected.oneShot && <span className={s.detailOnce}>유한 재생 — 실사용은 1~2회, 카탈로그에서만 반복</span>}
                            </div>
                            <div className={shared.desc}>{selected.intent}</div>
                            <code className={s.code}>{selected.example}</code>
                        </>
                    ) : (
                        <div className={shared.desc}>타일을 선택하면 의도와 사용 코드가 표시됩니다.</div>
                    )}
                </div>
            </div>
        </div>
    );
}
