export type Actor = {id: number; username: string; displayName: string; role: 'HEADQUARTERS'|'REGION'|'STORE'}
export type Store = {id: number; name: string; regionId: number; regionName: string}
export type Period = {from: string; to: string}
export type Stats = {orders: number; refundedOrders: number; grossSales: number; refundAmount: number; netSales: number; refundRate: number}
export type Dashboard = { period:Period; comparison:Period; storeIds:number[]; current:Stats; previous:Stats;
  change:{ordersPercent:number|null;grossSalesPercent:number|null;refundRatePoints:number};
  trend:(Pick<Stats,'orders'|'refundedOrders'|'grossSales'|'refundAmount'> & {day:string})[]}
export type Document = {id:string;title:string;source:string}
export type Citation = {id:string;documentId:string;title:string;source:string;ordinal:number;content:string}
export type Step = {stage:string;message:string;elapsedMs:number}
export type Draft = {mode:'model'|'evidence';advice:string;anomaly:'NORMAL'|'REFUND_RATE_RISE'|'NO_DATA';metrics:Dashboard;citations:Citation[];steps:Step[]}
export type Session = {id:string;question:string;state:'RUNNING'|'DRAFT'|'CONFIRMED'|'FAILED';draft:Draft|null;failure:string|null;createdAt:string}
export type Report = {id:string;analysisId:string;content:Draft;confirmedAt:string}
export type Audit = {id:number;actionCode:string;target:string;outcome:string;createdAt:string}
export type Detail = {id:number;storeId:number;amount:number;orderedAt?:string;refundedAt?:string;reason?:string}
