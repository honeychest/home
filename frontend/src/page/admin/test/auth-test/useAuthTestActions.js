import { useState } from 'react';
import { login, fetchCookieDebug } from '@/api/adminTest/auth.js';
import { fetchArchiveCount, runArchive, runArchiveUpload, fetchScanPreview, runScan } from '@/api/adminTest/archive.js';
import { logApiCall } from '../shared/logApiCall.js';

const toMs = (datetimeLocal) => new Date(datetimeLocal).getTime();
const ARCHIVE_DISABLED = true;

export default function useAuthTestActions() {
    const [email, setEmail] = useState('');
    const [password, setPassword] = useState('');
    const [archiveFrom, setArchiveFrom] = useState('');
    const [archiveTo, setArchiveTo] = useState('');
    const [archiveCount, setArchiveCount] = useState(null);
    const [, setScanFiles] = useState(null);
    const [runningAction, setRunningAction] = useState(null);
    const [logs, setLogs] = useState({});

    const busy = runningAction != null;
    const patchLog = (key, log) => setLogs(prev => ({ ...prev, [key]: log }));

    const handleLogin = async (e) => {
        e.preventDefault();
        if (busy || !email.trim()) return;
        setRunningAction('login');
        const log = await logApiCall('POST /api/auth/login', () => login({ email: email.trim(), password }));
        patchLog('login', log);
        setRunningAction(null);
    };

    const handleCookieSnapshot = async () => {
        if (busy) return;
        setRunningAction('cookieSnapshot');
        const log = await logApiCall('GET /api/admin/test/auth/debug/cookie-info', fetchCookieDebug);
        patchLog('cookieSnapshot', log);
        setRunningAction(null);
    };

    const handleArchiveCount = async () => {
        if (ARCHIVE_DISABLED || busy || !archiveFrom || !archiveTo) return;
        setArchiveCount(null);
        setRunningAction('archiveCount');
        const log = await logApiCall('POST /api/admin/archive/count', () => fetchArchiveCount(toMs(archiveFrom), toMs(archiveTo)));
        patchLog('archive', { ...log, _isCount: true });
        if (log.ok && log.responseBody?.count != null) {
            setArchiveCount(log.responseBody.count);
        }
        setRunningAction(null);
    };

    const handleArchiveRun = async () => {
        if (ARCHIVE_DISABLED || busy || archiveCount == null) return;
        setRunningAction('archiveRun');
        const log = await logApiCall('POST /api/admin/archive/run', () => runArchive(toMs(archiveFrom), toMs(archiveTo)));
        patchLog('archive', log);
        setArchiveCount(null);
        setRunningAction(null);
    };

    const handleArchiveUpload = async () => {
        if (ARCHIVE_DISABLED || busy || archiveCount == null) return;
        setRunningAction('archiveUpload');
        const log = await logApiCall('POST /api/admin/archive/upload', () => runArchiveUpload(toMs(archiveFrom), toMs(archiveTo)));
        patchLog('archive', log);
        setArchiveCount(null);
        setRunningAction(null);
    };

    const handleScanPreview = async () => {
        if (ARCHIVE_DISABLED || busy) return;
        setScanFiles(null);
        setRunningAction('scanPreview');
        const log = await logApiCall('GET /api/admin/archive/scan-preview', fetchScanPreview);
        patchLog('archiveScan', log);
        if (log.ok && Array.isArray(log.responseBody)) {
            setScanFiles(log.responseBody);
        }
        setRunningAction(null);
    };

    const handleScanRun = async () => {
        if (ARCHIVE_DISABLED || busy) return;
        setRunningAction('scanRun');
        const log = await logApiCall('POST /api/admin/archive/scan', runScan);
        patchLog('archiveScan', log);
        setRunningAction(null);
    };

    return {
        email, setEmail, password, setPassword,
        archiveFrom, setArchiveFrom, archiveTo, setArchiveTo, archiveCount, setArchiveCount,
        runningAction, busy, logs, archiveDisabled: ARCHIVE_DISABLED,
        handleLogin, handleCookieSnapshot,
        handleArchiveCount, handleArchiveRun, handleArchiveUpload,
        handleScanPreview, handleScanRun,
    };
}
