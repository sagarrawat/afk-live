import { useQuery } from '@tanstack/react-query';
import { ErrorNote, Panel, Spinner } from '@/components/ui';
import { get } from '@/lib/api';
import { Meter } from '../Admin';

/** AI-1 FinOps: this UTC month's AI spend (GET /api/admin/ai-spend). Money in US dollars. */
export interface AiSpend {
  month: string;
  provider?: string | null;
  model?: string | null;
  spentUsd: number;
  budgetUsd: number;
  percent?: number | null;
  stopped: boolean;
  inputPerMillion: number;
  outputPerMillion: number;
  rows: { provider: string; model: string; calls: number; inputTokens: number; outputTokens: number; costUsd: number }[];
}

const PROVIDER: Record<string, string> = { anthropic: 'Claude (Anthropic)', gemini: 'Gemini (Google)' };

/** Dollars: cents for bigger amounts, more digits for the fractions of a cent a call costs. */
export function usd(v: number): string {
  return `$${v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: v < 1 ? 4 : 2 })}`;
}

const tokens = (n: number) => n.toLocaleString('en-US');

/** On admin Billing: what AI writing cost this month against the monthly cap, by model. */
export function AiSpendPanel() {
  const q = useQuery({ queryKey: ['admin', 'ai-spend'], queryFn: () => get<AiSpend>('/api/admin/ai-spend'), refetchInterval: 300000 });
  if (q.isLoading) return <Spinner />;
  if (!q.data) return <ErrorNote error={q.error} />;
  const s = q.data;
  return (
    <Panel title="AI spend">
      <div className="space-y-3">
        <p className="text-sm">
          {s.provider ? (
            <>
              Writing with <span className="font-medium">{PROVIDER[s.provider] ?? s.provider}</span>, {s.model}.
            </>
          ) : (
            'AI writing is off: no ANTHROPIC_API_KEY or GEMINI_API_KEY is set.'
          )}
        </p>
        {s.budgetUsd > 0 ? (
          <Meter
            label={`This month (${s.month}, UTC)`}
            pct={s.percent ?? 0}
            text={`${usd(s.spentUsd)} of ${usd(s.budgetUsd)} (${Math.floor(s.percent ?? 0)}%)`}
          />
        ) : (
          <p className="text-sm text-muted">The monthly cap is $0 (APP_AI_MONTHLY_BUDGET_USD), so nothing calls AI.</p>
        )}
        {s.stopped && s.budgetUsd > 0 && (
          <p className="rounded-md border border-warn/40 bg-warn/10 px-3 py-2 text-sm" data-testid="ai-stopped">
            The cap is reached: creators see “isn’t available right now” until the month ends. Raise APP_AI_MONTHLY_BUDGET_USD to allow more.
          </p>
        )}
        {s.rows.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[480px] text-sm">
              <thead className="text-left text-xs text-muted">
                <tr>
                  <th className="py-1.5 pr-3 font-medium">Model</th>
                  <th className="py-1.5 pr-3 text-right font-medium">Calls</th>
                  <th className="py-1.5 pr-3 text-right font-medium">Tokens in</th>
                  <th className="py-1.5 pr-3 text-right font-medium">Tokens out</th>
                  <th className="py-1.5 text-right font-medium">Cost</th>
                </tr>
              </thead>
              <tbody className="tnum">
                {s.rows.map((r) => (
                  <tr key={`${r.provider}/${r.model}`} className="border-t border-line">
                    <td className="py-1.5 pr-3">{r.model}</td>
                    <td className="py-1.5 pr-3 text-right">{tokens(r.calls)}</td>
                    <td className="py-1.5 pr-3 text-right">{tokens(r.inputTokens)}</td>
                    <td className="py-1.5 pr-3 text-right">{tokens(r.outputTokens)}</td>
                    <td className="py-1.5 text-right font-medium">{usd(r.costUsd)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className="tnum text-xs text-muted">
          Tokens as the API reports them, at {usd(s.inputPerMillion)} in / {usd(s.outputPerMillion)} out per million (APP_AI_PRICE_INPUT_PER_MTOK,
          APP_AI_PRICE_OUTPUT_PER_MTOK). Admins are alerted at 80% and 100%; at 100% AI stops until the next UTC month.
        </p>
      </div>
    </Panel>
  );
}
