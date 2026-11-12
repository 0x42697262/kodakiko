import { FLOW_ID_DASH } from './constants.ts';

export type UiProject = 'desktop' | 'mobile' | 'android';

export function uiUsername(flowId: string, project: UiProject, index: number): string {
  const flowPart = flowId.replace(FLOW_ID_DASH, '').toLowerCase();
  return `ui-${flowPart}-${project}-${index}`;
}
