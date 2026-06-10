// [AGENT] T4-ANALYSIS: 템플릿 바 — 드롭다운 불러오기 + [관리] 버튼 + 저장 입력 플로우
import { useRef, useEffect } from 'react';

export default function TemplateBar({
  templates,
  selectedId,
  onSelect,
  onManage,
  saveState,
  onSaveClick,
  onSaveConfirm,
  onSaveCancel,
}) {
  const inputRef = useRef(null);

  useEffect(() => {
    if (saveState === 'input' && inputRef.current) {
      inputRef.current.focus();
      inputRef.current.select();
    }
  }, [saveState]);

  const handleKeyDown = (e) => {
    if (e.key === 'Enter')  onSaveConfirm(e.target.value.trim());
    if (e.key === 'Escape') onSaveCancel();
  };

  if (saveState === 'input' || saveState === 'saving') {
    const current = templates.find((t) => t.id === selectedId);
    const defaultName = current?.name ?? '';

    return (
      <div className="analysis-tb">
        <div className="analysis-tb__prompt-stack">
          <span className="analysis-tb__prompt">템플릿 이름을 확인해주세요.</span>
          <span className="analysis-tb__hint">같은 이름으로 저장하면 기존 템플릿이 덮어쓰기 되고, 다른 이름이면 새 템플릿이 생성됩니다.</span>
        </div>
        <input
          ref={inputRef}
          type="text"
          className="analysis-input analysis-input--focus-strong analysis-tb__input"
          defaultValue={defaultName}
          placeholder="템플릿 이름 입력 후 Enter"
          disabled={saveState === 'saving'}
          onKeyDown={handleKeyDown}
        />
        {saveState === 'saving' ? (
          <div className="analysis-spinner" />
        ) : (
          <button onClick={onSaveCancel} className="analysis-btn">취소</button>
        )}
      </div>
    );
  }

  return (
    <div className="analysis-tb">
      <span className="analysis-tb__label">템플릿</span>
      <select
        className="analysis-select analysis-tb__select"
        value={selectedId ?? ''}
        onChange={(e) => {
          if (e.target.value === '') {
            onSelect(null);
            return;
          }
          const t = templates.find((t) => String(t.id) === e.target.value);
          if (t) onSelect(t);
        }}
      >
        <option value="">-- 선택 --</option>
        {templates.map((t) => (
          <option key={t.id} value={t.id}>{t.name}</option>
        ))}
      </select>
      <button onClick={onSaveClick} className="analysis-btn analysis-btn--primary">저장</button>
      <button onClick={onManage}    className="analysis-btn">관리</button>
    </div>
  );
}
