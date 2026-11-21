import React, { useEffect, useMemo, useState } from 'react';
import { Copy, Check, Search, FileCode2, Folder } from 'lucide-react';
import type { MonorepoFileEntry } from '../types/protocol';

export function SourceExplorer() {
  const [files, setFiles] = useState<MonorepoFileEntry[]>([]);
  const [selectedPath, setSelectedPath] = useState<string>('README.md');
  const [fileContent, setFileContent] = useState<string>('');
  const [loadingFile, setLoadingFile] = useState<boolean>(false);
  const [search, setSearch] = useState<string>('');
  const [categoryFilter, setCategoryFilter] = useState<string>('all');
  const [copied, setCopied] = useState<boolean>(false);

  useEffect(() => {
    fetch('/api/monorepo/tree')
      .then((r) => r.json())
      .then((data) => {
        const list: MonorepoFileEntry[] = data.files ?? [];
        setFiles(list);
        if (list.length > 0 && !list.some((f) => f.path === selectedPath)) {
          setSelectedPath(list[0].path);
        }
      })
      .catch(() => setFiles([]));
  }, []);

  useEffect(() => {
    if (!selectedPath) return;
    setLoadingFile(true);
    fetch(`/api/monorepo/file?path=${encodeURIComponent(selectedPath)}`)
      .then((r) => r.json())
      .then((data) => setFileContent(data.content ?? ''))
      .finally(() => setLoadingFile(false));
  }, [selectedPath]);

  const categories = useMemo(() => ['all', 'android', 'ios', 'shared', 'tests', 'docs'], []);

  const filteredFiles = useMemo(() => {
    const q = search.trim().toLowerCase();
    return files.filter((f) => {
      const catMatch = categoryFilter === 'all' || f.category === categoryFilter;
      const qMatch = !q || f.path.toLowerCase().includes(q);
      return catMatch && qMatch;
    });
  }, [files, search, categoryFilter]);

  const copyContent = async () => {
    await navigator.clipboard.writeText(fileContent);
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1500);
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-col md:flex-row md:items-end justify-between gap-4 border-b border-slate-200 pb-6">
        <div className="space-y-1">
          <h1 className="text-2xl font-semibold tracking-tight text-slate-900">
            Monorepo Source Explorer
          </h1>
          <p className="text-sm text-slate-600">
            Inspect all {files.length} native Android Kotlin, iOS SwiftUI, shared JSON schema, and test files inside /media-remote.
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-1 p-1 bg-slate-100 rounded-lg">
          {categories.map((cat) => (
            <button
              key={cat}
              onClick={() => setCategoryFilter(cat)}
              className={`px-3 py-1.5 text-xs font-medium rounded-md transition-colors cursor-pointer capitalize ${
                categoryFilter === cat
                  ? 'bg-white text-slate-900 shadow-sm'
                  : 'text-slate-600 hover:text-slate-900'
              }`}
            >
              {cat}
            </button>
          ))}
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
        {/* File Tree Sidebar */}
        <aside className="lg:col-span-4 bg-white border border-slate-200 rounded-xl overflow-hidden">
          <div className="p-3 border-b border-slate-200">
            <div className="relative">
              <Search className="w-4 h-4 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2" />
              <input
                type="text"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Filter files by path..."
                className="w-full pl-9 pr-3 py-1.5 text-xs bg-slate-50 border border-slate-200 rounded-md focus:outline-none focus:border-blue-600"
              />
            </div>
          </div>

          <div className="max-h-[620px] overflow-y-auto divide-y divide-slate-100">
            {filteredFiles.length === 0 ? (
              <div className="p-6 text-center text-xs text-slate-500">No files match &ldquo;{search}&rdquo;</div>
            ) : (
              filteredFiles.map((file) => {
                const active = file.path === selectedPath;
                return (
                  <button
                    key={file.path}
                    onClick={() => setSelectedPath(file.path)}
                    className={`w-full text-left px-3.5 py-2.5 flex items-center justify-between gap-2 transition-colors cursor-pointer ${
                      active ? 'bg-blue-50 text-blue-800' : 'hover:bg-slate-50 text-slate-700'
                    }`}
                  >
                    <span className="flex items-center gap-2 min-w-0">
                      <FileCode2 className="w-3.5 h-3.5 shrink-0 text-slate-400" />
                      <span className="font-mono text-[11px] truncate">{file.path}</span>
                    </span>
                    <span className="font-mono text-[11px] text-slate-400 shrink-0">
                      {(file.size / 1024).toFixed(1)}k
                    </span>
                  </button>
                );
              })
            )}
          </div>
        </aside>

        {/* File Viewer */}
        <section className="lg:col-span-8 bg-white border border-slate-200 rounded-xl overflow-hidden">
          <div className="flex items-center justify-between gap-4 px-4 py-3 border-b border-slate-200 bg-slate-50">
            <div className="flex items-center gap-2 min-w-0">
              <Folder className="w-4 h-4 text-slate-500 shrink-0" />
              <span className="font-mono text-xs font-medium text-slate-900 truncate">
                media-remote/{selectedPath}
              </span>
            </div>
            <button
              onClick={copyContent}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium bg-white border border-slate-300 text-slate-700 rounded-md hover:bg-slate-100 cursor-pointer shrink-0"
            >
              {copied ? <Check className="w-3.5 h-3.5 text-emerald-600" /> : <Copy className="w-3.5 h-3.5" />}
              {copied ? 'Copied' : 'Copy'}
            </button>
          </div>

          <div className="bg-slate-950 text-slate-100 p-4 max-h-[620px] overflow-auto">
            {loadingFile ? (
              <div className="text-xs text-slate-400 font-mono py-8 text-center">Loading file...</div>
            ) : (
              <pre className="text-xs leading-relaxed font-mono whitespace-pre">
                <code>{fileContent}</code>
              </pre>
            )}
          </div>
        </section>
      </div>
    </div>
  );
}
