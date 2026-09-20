const api = async (path, options = {}) => {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options
  });
  const text = await response.text();
  const body = text ? JSON.parse(text) : {};
  if (!response.ok) {
    throw new Error(body.error || ('HTTP ' + response.status));
  }
  return body;
};

const short = (value) => value ? String(value).slice(0, 14) : '';
const esc = (value) => String(value ?? '').replace(/[&<>"]/g, (c) =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

const showResult = (id, message, ok) => {
  const element = document.getElementById(id);
  element.textContent = message;
  element.className = 'result ' + (ok ? 'ok' : 'err');
};

// ---------- view switching ----------
document.querySelectorAll('.navbtn').forEach((button) => {
  button.addEventListener('click', () => {
    document.querySelectorAll('.navbtn').forEach((b) => b.classList.remove('active'));
    button.classList.add('active');
    document.querySelectorAll('.view').forEach((view) => view.classList.add('hidden'));
    document.getElementById('view-' + button.dataset.view).classList.remove('hidden');
    if (button.dataset.view === 'runs') { loadRuns(); }
    if (button.dataset.view === 'exemptions') { loadExemptions(); }
    if (button.dataset.view === 'events') { loadEvents(); }
  });
});

// ---------- imports ----------
document.getElementById('spec-upload').addEventListener('click', async () => {
  try {
    const result = await api('/api/specs', {
      method: 'POST',
      body: JSON.stringify({
        name: document.getElementById('spec-name').value || 'spec',
        role: document.getElementById('spec-role').value,
        mediaType: document.getElementById('spec-media').value,
        content: document.getElementById('spec-content').value
      })
    });
    showResult('spec-result',
      `已保存规范 #${result.id}，指纹 ${result.fingerprint}` +
      (result.deduplicated ? '（内容重复，返回既有记录，未新建）' : ''), true);
    loadSpecs();
  } catch (error) {
    showResult('spec-result', error.message, false);
  }
});

document.getElementById('batch-upload').addEventListener('click', async () => {
  try {
    const parsed = JSON.parse(document.getElementById('batch-content').value);
    const name = document.getElementById('batch-name').value || 'batch';
    const samples = Array.isArray(parsed) ? parsed : parsed.samples;
    const result = await api('/api/sample-batches?name=' + encodeURIComponent(name), {
      method: 'POST',
      body: JSON.stringify(Array.isArray(parsed) ? parsed : { name, samples })
    });
    showResult('batch-result',
      `已保存样本集合 #${result.id}，样本 ${result.sampleCount} 条，快照 ${result.snapshotFingerprint}` +
      (result.deduplicated ? '（相同快照，返回既有集合）' : ''), true);
    loadBatches();
  } catch (error) {
    showResult('batch-result',
      error.message.includes('JSON') ? '样本 JSON 解析失败：' + error.message : error.message, false);
  }
});

const loadSpecs = async () => {
  const specs = await api('/api/specs');
  document.querySelector('#spec-table tbody').innerHTML = specs.map((spec) =>
    `<tr><td>${spec.id}</td><td>${esc(spec.name)}</td><td>${spec.role}</td>
     <td class="mono">${short(spec.fingerprint)}…</td></tr>`).join('');
};

const loadBatches = async () => {
  const batches = await api('/api/sample-batches');
  document.querySelector('#batch-table tbody').innerHTML = batches.map((batch) =>
    `<tr><td>${batch.id}</td><td>${esc(batch.name)}</td><td>${batch.sample_count}</td>
     <td class="mono">${short(batch.snapshot_fingerprint)}…</td></tr>`).join('');
};

// ---------- runs ----------
document.getElementById('run-create').addEventListener('click', async () => {
  try {
    const result = await api('/api/runs', {
      method: 'POST',
      body: JSON.stringify({
        baselineId: Number(document.getElementById('run-baseline').value),
        candidateId: Number(document.getElementById('run-candidate').value),
        sampleBatchId: Number(document.getElementById('run-batch').value),
        policyVersion: document.getElementById('run-policy').value
      })
    });
    showResult('run-result',
      `排练 #${result.id} 指纹 ${result.runFingerprint}` +
      (result.deduplicated ? '（命中由四要素决定的缓存）' : '（已计算并缓存）'), true);
    currentRunId = result.id;
  } catch (error) {
    showResult('run-result', error.message, false);
  }
});

let currentRunId = null;
let currentReport = null;

const loadRuns = async () => {
  const runs = await api('/api/runs');
  document.querySelector('#run-table tbody').innerHTML = runs.map((run) =>
    `<tr class="clickable" data-id="${run.id}">
       <td>${run.id}</td><td>${esc(run.policy_version)}</td>
       <td>${run.baseline_id} <span class="mono">${short(run.baseline_fingerprint)}</span></td>
       <td>${run.candidate_id} <span class="mono">${short(run.candidate_fingerprint)}</span></td>
       <td>${run.sample_batch_id} <span class="mono">${short(run.snapshot_fingerprint)}</span></td>
       <td class="mono">${short(run.run_fingerprint)}…</td>
       <td><button class="smallbtn open-run" data-id="${run.id}">查看</button></td>
     </tr>`).join('');
  document.querySelectorAll('.open-run').forEach((button) =>
    button.addEventListener('click', (event) => {
      event.stopPropagation();
      openReport(Number(button.dataset.id));
    }));
};

const openReport = async (runId) => {
  currentRunId = runId;
  currentReport = await api(`/api/runs/${runId}/report`);
  document.getElementById('report-area').classList.remove('hidden');
  document.getElementById('sample-area').classList.add('hidden');
  renderSummary(currentReport);
  renderEvidence(currentReport.evidence);
  renderFindings(currentReport);
  renderSamples(currentReport);
};

document.getElementById('drill-back').addEventListener('click', () => {
  document.getElementById('report-area').classList.add('hidden');
});
document.getElementById('sample-back').addEventListener('click', () => {
  document.getElementById('sample-area').classList.add('hidden');
  document.getElementById('report-area').classList.remove('hidden');
});

const badge = (severity) =>
  `<span class="badge ${String(severity).toLowerCase()}">${esc(severity)}</span>`;

const renderSummary = (report) => {
  const summary = report.summary;
  document.getElementById('summary').innerHTML = `
    <div class="statgrid">
      <div class="stat"><b>${summary.verdict}</b><span>总体结论</span></div>
      <div class="stat"><b>${summary.breaking}</b><span>破坏性变化</span></div>
      <div class="stat"><b>${summary.nonBreaking}</b><span>非破坏性变化</span></div>
      <div class="stat"><b>${summary.info}</b><span>提示</span></div>
      <div class="stat"><b>${summary.passRatePercent}%</b><span>通过率（证据样本除外）</span></div>
      <div class="stat"><b>${summary.samplesPassed}/${summary.samplesCounted}</b><span>通过 / 计入样本</span></div>
      <div class="stat"><b>${summary.samplesExcludedAsEvidence}</b><span>证据隔离样本</span></div>
      <div class="stat"><b>${summary.structuralProblemCount}</b><span>不可解析引用/循环</span></div>
    </div>
    <dl class="kv">
      <dt>策略版本</dt><dd>${esc(report.policyVersion)}</dd>
      <dt>基线指纹</dt><dd class="mono">${esc(report.baselineFingerprint)}</dd>
      <dt>候选指纹</dt><dd class="mono">${esc(report.candidateFingerprint)}</dd>
    </dl>`;
};

const renderEvidence = (evidence) => {
  const row = (title, values) =>
    `<div><b>${title}</b> ${values.length === 0 ? '（无）' : ''}
       ${values.map((value) => `<span class="badge evidence-badge mono">${esc(value)}</span>`).join(' ')}</div>`;
  document.getElementById('evidence').innerHTML =
    row('基线不可解析引用', evidence.baselineUnresolvableRefs) +
    row('候选不可解析引用', evidence.candidateUnresolvableRefs) +
    row('基线循环 schema', evidence.baselineCycles) +
    row('候选循环 schema', evidence.candidateCycles);
};

const exemptionById = (report) => {
  const map = new Map();
  (report.exemptions || []).forEach((exemption) => {
    if (exemption.effectiveStatus === 'ACTIVE') {
      map.set(exemption.findingId, exemption);
    }
  });
  return map;
};

const renderFindings = (report) => {
  const exemptionMap = exemptionById(report);
  document.querySelector('#findings-table tbody').innerHTML = report.findings.map((finding) => {
    const exemption = exemptionMap.get(finding.id);
    return `<tr>
      <td>${finding.id}</td>
      <td>${badge(finding.severity)}</td>
      <td>${esc(finding.code)}</td>
      <td>${esc(finding.side)}</td>
      <td>${esc(finding.method)}</td>
      <td class="mono">${esc(finding.path)}</td>
      <td class="mono" title="${esc(finding.locator)}">${esc(finding.subject || finding.anchor)}</td>
      <td>${esc(finding.summary)}</td>
      <td>${exemption
        ? `<span class="badge ACTIVE">已豁免</span>`
        : `<button class="smallbtn grant-btn" data-finding="${finding.id}">授予豁免</button>`}
      </td>
    </tr>`;
  }).join('');
  document.querySelectorAll('.grant-btn').forEach((button) =>
    button.addEventListener('click', () => openGrantModal(button.dataset.finding)));
};

const verdictBadge = (verdict) => {
  const className = verdict === 'PASS' ? 'pass'
    : verdict === 'FAIL' ? 'fail' : 'evidence_only';
  return `<span class="badge ${className}">${verdict}</span>`;
};

const validationCell = (request, response) =>
  `请求:${request.valid ? '✓' : '✗'} 响应:${response.valid ? '✓' : '✗'}` +
  (!request.complete || !response.complete ? ' <span class="badge evidence-badge">不完整</span>' : '');

const renderSamples = (report) => {
  document.querySelector('#samples-table tbody').innerHTML = report.samples.map((sample) =>
    `<tr class="clickable sample-row" data-key="${esc(sample.key)}">
      <td>${esc(sample.key)}</td>
      <td>${esc(sample.method)}</td>
      <td class="mono">${esc(sample.path)}</td>
      <td>${sample.statusCode ?? ''}</td>
      <td>${validationCell(sample.oldRequest, sample.oldResponse)}</td>
      <td>${validationCell(sample.newRequest, sample.newResponse)}</td>
      <td>${verdictBadge(sample.sampleVerdict)}</td>
      <td>${(sample.evidenceKinds || []).map((kind) =>
        `<span class="badge evidence-badge">${esc(kind)}</span>`).join(' ')}</td>
    </tr>`).join('');
  document.querySelectorAll('.sample-row').forEach((row) =>
    row.addEventListener('click', () => openSample(row.dataset.key)));
};

const validationBlock = (title, validation) => `
  <div class="card">
    <h3>${title} ${validation.valid
      ? '<span class="badge pass">有效</span>'
      : '<span class="badge fail">无效</span>'}
      ${validation.complete ? '' : '<span class="badge evidence_badge">不完整（证据）</span>'}</h3>
    <div><b>问题：</b>${validation.issues.length === 0 ? '无' : '<ul>' +
      validation.issues.map((issue) => `<li>${esc(issue)}</li>`).join('') + '</ul>'}</div>
    <div><b>证据：</b>${validation.evidence.length === 0 ? '无' : validation.evidence.map((evidence) =>
      `<span class="badge evidence-badge">${esc(evidence.kind)}: ${esc(evidence.detail)}</span>`).join(' ')}</div>
  </div>`;

const openSample = async (key) => {
  const detail = await api(`/api/runs/${currentRunId}/samples/${encodeURIComponent(key)}`);
  const sample = detail.sample;
  document.getElementById('sample-detail').innerHTML = `
    <dl class="kv">
      <dt>样本</dt><dd>${esc(sample.key)}</dd>
      <dt>请求</dt><dd>${esc(sample.method)} <span class="mono">${esc(sample.path)}</span></dd>
      <dt>记录状态码</dt><dd>${sample.statusCode ?? '（无）'}</dd>
      <dt>命中的变化</dt><dd>${(sample.hitFindingIds || []).join(', ') || '无'}</dd>
      <dt>证据类型</dt><dd>${(sample.evidenceKinds || []).join(', ') || '无'}</dd>
    </dl>
    ${validationBlock('旧验证（基线）· 请求', sample.oldRequest)}
    ${validationBlock('新验证（候选）· 请求', sample.newRequest)}
    ${validationBlock('旧验证（基线）· 响应', sample.oldResponse)}
    ${validationBlock('新验证（候选）· 响应', sample.newResponse)}
    <div class="card"><h3>响应消费方可能看到的差别</h3>
      ${(sample.consumerDeltas || []).length === 0 ? '<p>无具体命中差别。</p>' :
        sample.consumerDeltas.map((delta) => `
          <div class="card">
            <b>${delta.findingId} ${esc(delta.code)}</b> ${badge(delta.severity)}
            <p>${esc(delta.detail)}</p>
            <pre>${esc(JSON.stringify({ oldValid: delta.oldValid, newValid: delta.newValid,
              newIssues: delta.newIssues }, null, 2))}</pre>
          </div>`).join('')}
    </div>`;
  document.getElementById('report-area').classList.add('hidden');
  document.getElementById('sample-area').classList.remove('hidden');
};

// ---------- exemption modal ----------
let modalFindingId = null;
const openGrantModal = (findingId) => {
  modalFindingId = findingId;
  const finding = currentReport.findings.find((item) => item.id === findingId);
  const now = Date.now();
  document.getElementById('modal-title').textContent = `授予豁免：${findingId}`;
  document.getElementById('modal-content').innerHTML = `
    <p class="mono">${esc(finding.method)} ${esc(finding.path)} — ${esc(finding.code)}<br>${esc(finding.locator)}</p>
    <label>操作者 <input id="grant-actor" placeholder="alice"></label>
    <label>理由（必填，会写入审计事件）<textarea id="grant-reason" rows="3"></textarea></label>
    <div class="formrow">
      <label>生效开始 (epoch ms)<input id="grant-from" type="number" value="${now}"></label>
      <label>生效截止 (epoch ms)<input id="grant-to" type="number" value="${now + 14 * 86400000}"></label>
    </div>`;
  document.getElementById('modal').classList.remove('hidden');
};

document.getElementById('modal-cancel').addEventListener('click', () =>
  document.getElementById('modal').classList.add('hidden'));

document.getElementById('modal-submit').addEventListener('click', async () => {
  try {
    await api(`/api/runs/${currentRunId}/exemptions`, {
      method: 'POST',
      body: JSON.stringify({
        findingId: modalFindingId,
        reason: document.getElementById('grant-reason').value,
        actor: document.getElementById('grant-actor').value,
        validFromMs: Number(document.getElementById('grant-from').value),
        validToMs: Number(document.getElementById('grant-to').value)
      })
    });
    document.getElementById('modal').classList.add('hidden');
    await openReport(currentRunId);
  } catch (error) {
    alert(error.message);
  }
});

// ---------- exemptions / events ----------
const loadExemptions = async () => {
  const exemptions = await api('/api/exemptions');
  document.querySelector('#exemption-table tbody').innerHTML = exemptions.map((item) =>
    `<tr>
      <td>${item.id}</td><td>${item.runId}</td>
      <td>${item.findingId} ${esc(item.changeCode)}</td>
      <td class="mono">${esc(item.method)} ${esc(item.path)}<br>${esc(item.subject || item.anchor)}</td>
      <td class="mono">${item.validFromMs}<br>→ ${item.validToMs}</td>
      <td>${esc(item.actor)}</td>
      <td><span class="badge ${item.effectiveStatus}">${item.effectiveStatus}</span></td>
      <td>${esc(item.resolution || '')}</td>
      <td>${item.effectiveStatus === 'REVOKED' ? '' :
        `<button class="smallbtn danger revoke-btn" data-id="${item.id}">撤销</button>`}</td>
    </tr>`).join('');
  document.querySelectorAll('.revoke-btn').forEach((button) =>
    button.addEventListener('click', () => revokeExemption(Number(button.dataset.id))));
};

const revokeExemption = async (id) => {
  const actor = prompt('撤销操作者：');
  if (!actor) { return; }
  const reason = prompt('撤销理由（将产生新的审计事件，历史不删除）：');
  if (!reason) { return; }
  try {
    await api(`/api/exemptions/${id}/revoke`, {
      method: 'POST',
      body: JSON.stringify({ actor, reason })
    });
    loadExemptions();
  } catch (error) {
    alert(error.message);
  }
};

const loadEvents = async () => {
  const events = await api('/api/events');
  document.querySelector('#event-table tbody').innerHTML = events.map((event) =>
    `<tr>
      <td>${event.id}</td><td>${esc(event.event_type)}</td>
      <td>${event.run_id ?? ''}</td><td>${esc(event.finding_id || '')} ${esc(event.change_code || '')}</td>
      <td>${esc(event.actor || '')}</td><td>${esc(event.reason || '')}</td>
      <td class="mono">${short(event.before_version)}</td>
      <td class="mono">${short(event.after_version)}</td>
      <td class="mono">${event.created_at_ms}</td>
    </tr>`).join('');
};

// initial loads
loadSpecs();
loadBatches();
loadRuns();
