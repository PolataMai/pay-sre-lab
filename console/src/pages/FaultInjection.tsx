import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { FaultRule } from '../types/api';

const KNOWN_TYPES: FaultRule['type'][] = [
  'NONE',
  'TIMEOUT_BUT_SUCCESS',
  'TIMEOUT_BUT_FAILED',
  'DECLINE_ALL',
  'CALLBACK_LOST',
  'CALLBACK_DUPLICATED',
];

export function FaultInjection() {
  const [rules, setRules] = useState<FaultRule[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [channel, setChannel] = useState('CHANNEL_A');
  const [type, setType] = useState<FaultRule['type']>('NONE');
  const [probability, setProbability] = useState('1.00');
  const [activeForMinutes, setActiveForMinutes] = useState('2');
  const [seed, setSeed] = useState(() => Math.floor(Date.now() / 1000));

  function refresh() {
    api.listFaultRules()
      .then((data) => setRules(data))
      .catch((err) => setError(err.message));
  }

  useEffect(() => {
    refresh();
  }, []);

  async function install() {
    try {
      const now = new Date();
      const until = new Date(now.getTime() + Number(activeForMinutes) * 60_000);
      await api.installFaultRule({
        channel,
        type,
        probability: Number(probability),
        activeFrom: now.toISOString(),
        activeUntil: until.toISOString(),
        randomSeed: seed,
      });
      refresh();
    } catch (err) {
      setError((err as Error).message);
    }
  }

  return (
    <div className="card">
      <h2>Fault injection</h2>
      <p>
        Install a synthetic fault rule on a channel. Combine with a
        fault-scenario YAML in <code>fault-scenarios/</code> to replay
        the full investigation + four-eyes remediation flow.
      </p>
      {error && <p style={{ color: '#b91c1c' }}>{error}</p>}

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.6rem' }}>
        <div>
          <label>Channel</label>
          <input value={channel} onChange={(e) => setChannel(e.target.value)} />
        </div>
        <div>
          <label>Fault type</label>
          <select value={type} onChange={(e) => setType(e.target.value as FaultRule['type'])}>
            {KNOWN_TYPES.map((known) => (
              <option key={known} value={known}>
                {known}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label>Probability</label>
          <input
            value={probability}
            onChange={(e) => setProbability(e.target.value)}
            inputMode="decimal"
          />
        </div>
        <div>
          <label>Active for (minutes)</label>
          <input
            value={activeForMinutes}
            onChange={(e) => setActiveForMinutes(e.target.value)}
            inputMode="numeric"
          />
        </div>
        <div>
          <label>Random seed</label>
          <input value={seed} onChange={(e) => setSeed(Number(e.target.value))} />
        </div>
      </div>

      <p style={{ marginTop: '0.6rem' }}>
        <button onClick={install}>Install rule</button>
      </p>

      <h3>Active rules</h3>
      {rules.length === 0 ? (
        <p>No fault rules currently installed.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Channel</th>
              <th>Type</th>
              <th>Probability</th>
              <th>Window</th>
              <th>Seed</th>
            </tr>
          </thead>
          <tbody>
            {rules.map((rule) => (
              <tr key={`${rule.channel}:${rule.type}`}>
                <td>{rule.channel}</td>
                <td>{rule.type}</td>
                <td>{rule.probability.toFixed(2)}</td>
                <td>
                  {new Date(rule.activeFrom).toLocaleString()} –{' '}
                  {new Date(rule.activeUntil).toLocaleString()}
                </td>
                <td>{rule.randomSeed}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}