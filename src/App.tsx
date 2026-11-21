import React, { useState } from 'react';
import { Download } from 'lucide-react';
import { LiveTestbench } from './components/LiveTestbench';
import { SourceExplorer } from './components/SourceExplorer';
import { SetupAndArchitecture } from './components/SetupAndArchitecture';

type TabKey = 'testbench' | 'source' | 'architecture';

export default function App() {
  const [activeTab, setActiveTab] = useState<TabKey>('testbench');

  return (
    <div className="min-h-screen bg-[#f8fafc] text-slate-900 flex flex-col">
      {/* 3-Zone Top Bar Contract */}
      <header className="bg-white border-b border-slate-200">
        <div className="max-w-[1200px] mx-auto px-6 py-4 flex items-center justify-between gap-8">
          {/* Zone 1: Single-line Brand Wordmark */}
          <button
            onClick={() => setActiveTab('testbench')}
            className="font-display text-lg font-bold tracking-tight text-slate-900 whitespace-nowrap shrink-0 cursor-pointer"
          >
            LocalCast Remote
          </button>

          {/* Zone 2: Clean Typography Nav Links */}
          <nav className="hidden md:flex items-center gap-6 text-sm font-medium">
            <button
              onClick={() => setActiveTab('testbench')}
              className={`py-1 border-b-2 transition-colors whitespace-nowrap shrink-0 cursor-pointer ${
                activeTab === 'testbench'
                  ? 'border-blue-600 text-slate-900'
                  : 'border-transparent text-slate-600 hover:text-slate-900'
              }`}
            >
              Live Testbench
            </button>
            <button
              onClick={() => setActiveTab('source')}
              className={`py-1 border-b-2 transition-colors whitespace-nowrap shrink-0 cursor-pointer ${
                activeTab === 'source'
                  ? 'border-blue-600 text-slate-900'
                  : 'border-transparent text-slate-600 hover:text-slate-900'
              }`}
            >
              Source Explorer
            </button>
            <button
              onClick={() => setActiveTab('architecture')}
              className={`py-1 border-b-2 transition-colors whitespace-nowrap shrink-0 cursor-pointer ${
                activeTab === 'architecture'
                  ? 'border-blue-600 text-slate-900'
                  : 'border-transparent text-slate-600 hover:text-slate-900'
              }`}
            >
              Setup &amp; Architecture
            </button>
          </nav>

          {/* Zone 3: Single Primary Action */}
          <div className="shrink-0">
            <a
              href="/api/export-zip"
              download="media-remote-monorepo.zip"
              className="inline-flex items-center gap-2 px-4 py-2 text-xs font-medium bg-slate-900 text-white rounded-lg hover:bg-slate-800 transition-colors whitespace-nowrap shrink-0"
            >
              <Download className="w-3.5 h-3.5" />
              Download Project ZIP
            </a>
          </div>
        </div>

        {/* Mobile Secondary Nav Row */}
        <div className="flex md:hidden border-t border-slate-100 px-6 py-2 gap-4 overflow-x-auto text-xs font-medium">
          <button
            onClick={() => setActiveTab('testbench')}
            className={`whitespace-nowrap shrink-0 ${activeTab === 'testbench' ? 'text-blue-600' : 'text-slate-600'}`}
          >
            Live Testbench
          </button>
          <button
            onClick={() => setActiveTab('source')}
            className={`whitespace-nowrap shrink-0 ${activeTab === 'source' ? 'text-blue-600' : 'text-slate-600'}`}
          >
            Source Explorer
          </button>
          <button
            onClick={() => setActiveTab('architecture')}
            className={`whitespace-nowrap shrink-0 ${activeTab === 'architecture' ? 'text-blue-600' : 'text-slate-600'}`}
          >
            Setup &amp; Architecture
          </button>
        </div>
      </header>

      <main className="flex-1 max-w-[1200px] w-full mx-auto px-6 py-8">
        {activeTab === 'testbench' && <LiveTestbench />}
        {activeTab === 'source' && <SourceExplorer />}
        {activeTab === 'architecture' && <SetupAndArchitecture />}
      </main>
    </div>
  );
}
