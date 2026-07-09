// [AGENT] recipe UI 킷 — 버튼 (.rcp-btn / -ghost / -danger)
import type { ButtonHTMLAttributes, ReactNode } from 'react';

interface RcpButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
    variant?: 'primary' | 'ghost' | 'danger';
    children: ReactNode;
}

export default function RcpButton({ variant = 'primary', children, className = '', ...rest }: RcpButtonProps) {
    const variantClass = variant === 'ghost' ? 'rcp-btn-ghost' : variant === 'danger' ? 'rcp-btn-danger' : '';
    return (
        <button type="button" className={`rcp-btn ${variantClass} ${className}`.trim()} {...rest}>
            {children}
        </button>
    );
}
