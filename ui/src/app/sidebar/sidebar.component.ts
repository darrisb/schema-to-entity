import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { SchemaService } from '../services/schema.service';

@Component({
  selector: 'app-sidebar',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './sidebar.component.html',
  styles: [
    `
      :host {
        display: block;
        width: 260px;
        padding: 16px;
        border-right: 1px solid #ddd;
        background: #fafafa;
      }
      .section {
        display: flex;
        flex-direction: column;
        gap: 6px;
        margin-bottom: 16px;
      }
      label {
        font-size: 11px;
        font-weight: 600;
        text-transform: uppercase;
        color: #6b7280;
      }
      input {
        padding: 8px;
        border: 1px solid #d1d5db;
        border-radius: 6px;
        font-size: 13px;
        width: 100%;
        box-sizing: border-box;
      }
      .actions {
        display: flex;
        flex-direction: column;
        gap: 8px;
      }
      .summary {
        margin-top: 12px;
        color: #047857;
        font-size: 12px;
      }
      button {
        padding: 10px 12px;
        border: none;
        border-radius: 6px;
        background: #6d28d9;
        color: #fff;
        font-weight: 600;
        cursor: pointer;
      }
      button.secondary {
        background: #e5e7eb;
        color: #111827;
      }
      button:disabled {
        opacity: 0.5;
        cursor: not-allowed;
      }
      .error {
        margin-top: 12px;
        color: #b91c1c;
        font-size: 12px;
      }
    `
  ]
})
export class SidebarComponent {
  readonly schemaService = inject(SchemaService);
}
