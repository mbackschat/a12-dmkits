import { checkModelJson, checkDraftJson } from 'a12-consistency';
import storefront from './storefront.dm.json';
import broken from './storefront-broken.dm.json';
import { version } from './artifact.json';
import './style.css';

type Entry = { code?: string; at?: string | null; explain?: string; reason?: string; corrective?: { fix: string; meaning: string } | null; span?: { member: string; start: number; end: number } | null };
type Report = { status: string; complete: boolean; outcome?: string; findings: Entry[]; diagnostics: Entry[]; checksCompleted: string[]; gaps: Entry[] };
type DraftReport = { status: string; complete: boolean; introduced: Entry[]; resolved: Entry[]; gaps: Entry[]; baseline: Report; edited: Report };
const element = <T extends HTMLElement>(id: string): T => document.getElementById(id) as T;
const model = element<HTMLTextAreaElement>('model');
const sample = element<HTMLSelectElement>('sample');
const text = (id: string, value: string) => { element(id).textContent = value; };
function loadSample(): void {
  model.value = JSON.stringify(sample.value === 'broken' ? broken : storefront, null, 2);
  text('file-status', 'Local file contents stay in this browser. No upload, analytics or persistent storage.');
  element('stale').hidden = false;
}
function entries(target: HTMLElement, values: Entry[]): void {
  target.replaceChildren();
  if (!values.length) { target.textContent = 'None reported.'; return; }
  for (const value of values) {
    const card = document.createElement('article'); card.className = 'finding';
    const title = document.createElement('strong'); title.textContent = value.code ?? value.reason ?? 'Coverage gap'; card.append(title);
    for (const detail of [value.at, value.explain, value.span && `${value.span.member}: UTF-16 offsets ${value.span.start}–${value.span.end}`, value.corrective?.fix, value.corrective?.meaning]) {
      if (detail) { const p = document.createElement('p'); p.textContent = detail; card.append(p); }
    }
    target.append(card);
  }
}
function render(result: Report | DraftReport, draft: boolean): void {
  const edit = 'edited' in result ? result : undefined;
  const report = edit ? edit.edited : result as Report;
  text('result-title', draft ? 'Draft report · compared with the current model' : 'Model report');
  text('verdict', edit ? `Draft: ${edit.status} · candidate model: ${report.outcome} / ${report.status}` : `${report.outcome} / ${report.status}`);
  element('verdict').className = result.status === 'INVALID' ? 'verdict rejected' : 'verdict';
  text('meaning', edit ? 'The draft verdict concerns introduced findings. Candidate and baseline reports are retained in the full JSON.' : 'A scoped pass is not a full validity certificate. Read source diagnostics and coverage as well as findings.');
  text('changes', edit ? `Introduced: ${edit.introduced.length} · Resolved: ${edit.resolved.length} · Baseline: ${edit.baseline.outcome} / ${edit.baseline.status}` : '');
  entries(element('findings'), report.findings);
  entries(element('diagnostics'), report.diagnostics);
  const coverage = element('coverage'); coverage.replaceChildren();
  const heading = document.createElement('h3'); heading.textContent = 'Coverage'; coverage.append(heading);
  const p = document.createElement('p'); p.textContent = `complete: ${result.complete} · ${report.checksCompleted.length} check families completed · ${(edit ? edit.gaps : report.gaps).length} coverage gaps`; coverage.append(p);
  const families = document.createElement('details');
  const summary = document.createElement('summary'); summary.textContent = 'Completed check families'; families.append(summary);
  const list = document.createElement('p'); list.textContent = report.checksCompleted.join(', ') || 'None'; families.append(list); coverage.append(families);
  const gaps = document.createElement('div'); entries(gaps, edit ? edit.gaps : report.gaps); coverage.append(gaps);
  text('raw', JSON.stringify(result, null, 2));
  element('stale').hidden = true;
}
function run(draft: boolean): void {
  try {
    const raw = draft ? checkDraftJson(model.value, element<HTMLInputElement>('at').value, element<HTMLInputElement>('member').value, element<HTMLTextAreaElement>('draft').value) : checkModelJson(model.value);
    render(JSON.parse(raw), draft);
  } catch (error) {
    text('verdict', 'The showcase could not complete this check.');
    text('meaning', String(error));
    element('stale').hidden = false;
  }
}
text('version', version);
loadSample();
element('stale').hidden = true;
sample.addEventListener('change', loadSample);
element<HTMLInputElement>('file').addEventListener('change', async event => {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (!file) return;
  try { model.value = await file.text(); text('file-status', `Loaded locally: ${file.name}`); element('stale').hidden = false; }
  catch { text('file-status', 'This file could not be read.'); }
});
for (const id of ['model', 'at', 'member', 'draft']) element(id).addEventListener('input', () => { element('stale').hidden = false; });
element('check-model').addEventListener('click', () => run(false));
element('check-draft').addEventListener('click', () => run(true));
