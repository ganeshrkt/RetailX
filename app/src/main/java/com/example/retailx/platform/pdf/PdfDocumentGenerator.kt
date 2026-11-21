package com.example.retailx.platform.pdf

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.example.retailx.core.common.DateTimeUtils
import com.example.retailx.core.common.IndianRupeesInWords
import com.example.retailx.core.common.IndianStates
import com.example.retailx.core.common.MoneyFormatter
import com.example.retailx.core.common.QuantityScale
import com.example.retailx.core.model.*
import com.example.retailx.domain.repository.ReportsBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PdfDocumentGenerator(private val context: Context) {

    private fun invoicesDir(): File {
        return File(context.filesDir, "invoices").apply { mkdirs() }
    }

    suspend fun generateSaleInvoicePdf(
        store: StoreSettings,
        sale: Sale,
        format: InvoiceFormat
    ): File = withContext(Dispatchers.IO) {
        val file = File(invoicesDir(), "${sale.invoiceNo}_${format.name}.pdf")
        val doc = PdfDocument()
        try {
            if (format == InvoiceFormat.A4) {
                renderA4Invoice(doc, store, sale)
            } else {
                renderThermalReceipt(doc, store, sale, format)
            }
            FileOutputStream(file).use { out -> doc.writeTo(out) }
        } finally {
            doc.close()
        }
        file
    }

    suspend fun generateCreditNotePdf(
        store: StoreSettings,
        ret: SalesReturn
    ): File = withContext(Dispatchers.IO) {
        val file = File(invoicesDir(), "${ret.returnNo}.pdf")
        val doc = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
            val page = doc.startPage(pageInfo)
            val c = page.canvas
            val pTitle = paint(18f, bold = true)
            val pHead = paint(11f, bold = true)
            val pBody = paint(10f, bold = false)
            var y = 44f
            c.drawText(store.name, 40f, y, pTitle); y += 18f
            if (store.gstin.isNotBlank()) { c.drawText("GSTIN: ${store.gstin}", 40f, y, pBody); y += 14f }
            c.drawText(store.address, 40f, y, pBody); y += 22f
            c.drawLine(40f, y, 555f, y, linePaint()); y += 18f
            c.drawText("CREDIT NOTE / SALES RETURN", 40f, y, paint(14f, bold = true)); y += 18f
            c.drawText("Return No: ${ret.returnNo}   |   Original Invoice: ${ret.invoiceNo}", 40f, y, pHead); y += 15f
            c.drawText("Date: ${DateTimeUtils.formatDateTime(ret.createdAt, store.timezone)}   |   Refund Method: ${ret.refundMethod.label}", 40f, y, pBody); y += 15f
            c.drawText("Reason: ${ret.reason}", 40f, y, pBody); y += 20f
            c.drawLine(40f, y, 555f, y, linePaint()); y += 16f
            c.drawText("Item", 40f, y, pHead)
            c.drawText("Qty", 320f, y, pHead)
            c.drawText("Restock", 390f, y, pHead)
            c.drawText("Refund", 480f, y, pHead)
            y += 12f
            c.drawLine(40f, y, 555f, y, linePaint()); y += 16f
            for (item in ret.items) {
                c.drawText(item.description.take(42), 40f, y, pBody)
                c.drawText(QuantityScale.formatScaled(item.qtyScaled), 320f, y, pBody)
                c.drawText(if (item.restock) "Yes" else "No", 390f, y, pBody)
                c.drawText(MoneyFormatter.formatPaise(item.refundPaise), 480f, y, pBody)
                y += 16f
            }
            y += 8f
            c.drawLine(40f, y, 555f, y, linePaint()); y += 20f
            c.drawText("Total Refunded: ${MoneyFormatter.formatPaise(ret.totalRefundPaise)}", 40f, y, paint(13f, bold = true)); y += 16f
            c.drawText(IndianRupeesInWords.convert(ret.totalRefundPaise), 40f, y, pBody)
            doc.finishPage(page)
            FileOutputStream(file).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        file
    }

    suspend fun generatePurchasePdf(
        store: StoreSettings,
        purchase: Purchase
    ): File = withContext(Dispatchers.IO) {
        val file = File(invoicesDir(), "${purchase.purchaseNo}.pdf")
        val doc = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
            val page = doc.startPage(pageInfo)
            val c = page.canvas
            val pTitle = paint(17f, bold = true)
            val pHead = paint(10f, bold = true)
            val pBody = paint(9.5f, bold = false)
            var y = 44f
            c.drawText(store.name, 40f, y, pTitle); y += 18f
            c.drawText("PURCHASE DOCUMENT (${purchase.status.label})", 40f, y, paint(13f, bold = true)); y += 18f
            c.drawText("Purchase No: ${purchase.purchaseNo}   |   Supplier Inv: ${purchase.supplierInvoiceNo}", 40f, y, pBody); y += 14f
            c.drawText("Supplier: ${purchase.supplierName}   |   Tax Mode: ${if (purchase.isInterState) "IGST (Inter-State)" else "CGST + SGST (Intra-State)"}", 40f, y, pBody); y += 14f
            c.drawText("Date: ${DateTimeUtils.formatDateTime(purchase.createdAt, store.timezone)}", 40f, y, pBody); y += 18f
            c.drawLine(40f, y, 555f, y, linePaint()); y += 14f
            c.drawText("Item", 40f, y, pHead)
            c.drawText("Qty", 270f, y, pHead)
            c.drawText("Rate", 330f, y, pHead)
            c.drawText("GST", 410f, y, pHead)
            c.drawText("Total", 485f, y, pHead)
            y += 10f
            c.drawLine(40f, y, 555f, y, linePaint()); y += 16f
            for (item in purchase.items) {
                c.drawText(item.descriptionSnapshot.take(36), 40f, y, pBody)
                c.drawText("${QuantityScale.formatScaled(item.qtyScaled)} ${item.unitSnapshot}", 270f, y, pBody)
                c.drawText(MoneyFormatter.formatPaise(item.ratePaise), 330f, y, pBody)
                c.drawText("${item.gstRateBps / 100}%", 410f, y, pBody)
                c.drawText(MoneyFormatter.formatPaise(item.totalPaise), 485f, y, pBody)
                y += 16f
            }
            y += 10f
            c.drawLine(40f, y, 555f, y, linePaint()); y += 18f
            c.drawText("Taxable: ${MoneyFormatter.formatPaise(purchase.taxablePaise)}   |   Tax: ${MoneyFormatter.formatPaise(purchase.taxPaise)}", 40f, y, pBody); y += 16f
            c.drawText("Grand Total: ${MoneyFormatter.formatPaise(purchase.totalPaise)}   |   Paid: ${MoneyFormatter.formatPaise(purchase.paidPaise)}   |   Payable: ${MoneyFormatter.formatPaise(purchase.balancePayablePaise)}", 40f, y, pHead)
            doc.finishPage(page)
            FileOutputStream(file).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        file
    }

    suspend fun generateShiftReportPdf(
        store: StoreSettings,
        shift: CashRegisterShift
    ): File = withContext(Dispatchers.IO) {
        val file = File(invoicesDir(), "SHIFT_${shift.id.take(8)}.pdf")
        val doc = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
            val page = doc.startPage(pageInfo)
            val c = page.canvas
            val pTitle = paint(18f, bold = true)
            val pHead = paint(11f, bold = true)
            val pBody = paint(10.5f, bold = false)
            var y = 48f
            c.drawText("${store.name} - Cash Register Shift Report", 40f, y, pTitle); y += 22f
            c.drawText("Cashier: ${shift.userName}", 40f, y, pHead); y += 16f
            c.drawText("Opened: ${DateTimeUtils.formatDateTime(shift.openedAt, store.timezone)}", 40f, y, pBody); y += 16f
            c.drawText("Closed: ${shift.closedAt?.let { DateTimeUtils.formatDateTime(it, store.timezone) } ?: "Open"}", 40f, y, pBody); y += 20f
            c.drawLine(40f, y, 555f, y, linePaint()); y += 20f
            val rows = listOf(
                "Opening Cash" to MoneyFormatter.formatPaise(shift.openingCashPaise),
                "Cash Sales (+)" to MoneyFormatter.formatPaise(shift.cashSalesPaise),
                "Cash Refunds (-)" to MoneyFormatter.formatPaise(shift.cashRefundsPaise),
                "Cash Expenses (-)" to MoneyFormatter.formatPaise(shift.cashExpensesPaise),
                "Net Cash Movements" to MoneyFormatter.formatPaise(shift.cashMovementsNetPaise),
                "Expected Closing Cash" to MoneyFormatter.formatPaise(shift.expectedCashPaise),
                "Counted Closing Cash" to (shift.countedCashPaise?.let { MoneyFormatter.formatPaise(it) } ?: "-"),
                "Variance" to (shift.variancePaise?.let { MoneyFormatter.formatPaise(it) } ?: "-")
            )
            rows.forEach { (k, v) ->
                c.drawText(k, 40f, y, pBody)
                c.drawText(v, 420f, y, pHead)
                y += 18f
            }
            if (!shift.varianceReason.isNullOrBlank()) {
                y += 10f
                c.drawText("Variance Reason: ${shift.varianceReason}", 40f, y, pBody)
            }
            doc.finishPage(page)
            FileOutputStream(file).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        file
    }

    suspend fun generateBusinessReportPdf(
        store: StoreSettings,
        bundle: ReportsBundle
    ): File = withContext(Dispatchers.IO) {
        val file = File(invoicesDir(), "REPORT_${System.currentTimeMillis()}.pdf")
        val doc = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
            val page = doc.startPage(pageInfo)
            val c = page.canvas
            val pTitle = paint(17f, bold = true)
            val pHead = paint(11f, bold = true)
            val pBody = paint(10f, bold = false)
            var y = 44f
            c.drawText("${store.name} - Business & GST Summary", 40f, y, pTitle); y += 18f
            c.drawText(
                "Period: ${DateTimeUtils.formatDate(bundle.fromMillis, store.timezone)} to ${DateTimeUtils.formatDate(bundle.toMillis, store.timezone)} (${store.timezone})",
                40f, y, pBody
            ); y += 16f
            c.drawText("Note: Totals are tax-inclusive net of discounts & returns. Profit uses weighted-average cost estimate.", 40f, y, paint(8.5f, false, Color.DKGRAY)); y += 18f
            c.drawLine(40f, y, 555f, y, linePaint()); y += 18f

            val metrics = listOf(
                "Completed Bills" to bundle.billCount.toString(),
                "Gross Sales" to MoneyFormatter.formatPaise(bundle.grossSalesPaise),
                "Discounts" to MoneyFormatter.formatPaise(bundle.discountsPaise),
                "Returns" to MoneyFormatter.formatPaise(bundle.returnsPaise),
                "Net Sales" to MoneyFormatter.formatPaise(bundle.netSalesPaise),
                "Estimated COGS" to MoneyFormatter.formatPaise(bundle.cogsPaise),
                "Estimated Gross Profit" to MoneyFormatter.formatPaise(bundle.estimatedProfitPaise),
                "Recorded Expenses" to MoneyFormatter.formatPaise(bundle.expensesPaise),
                "Inventory Valuation" to MoneyFormatter.formatPaise(bundle.inventoryValuationPaise)
            )
            metrics.forEach { (k, v) ->
                c.drawText(k, 40f, y, pBody)
                c.drawText(v, 400f, y, pHead)
                y += 16f
            }
            y += 14f
            c.drawText("GST Breakdown by Rate", 40f, y, pHead); y += 14f
            c.drawLine(40f, y, 555f, y, linePaint()); y += 14f
            c.drawText("Slab", 40f, y, pHead)
            c.drawText("Taxable", 140f, y, pHead)
            c.drawText("CGST", 250f, y, pHead)
            c.drawText("SGST", 345f, y, pHead)
            c.drawText("IGST", 440f, y, pHead)
            y += 14f
            for (slab in bundle.gstSlabs) {
                c.drawText("${slab.gstRateBps / 100}%", 40f, y, pBody)
                c.drawText(MoneyFormatter.formatPaise(slab.taxablePaise), 140f, y, pBody)
                c.drawText(MoneyFormatter.formatPaise(slab.cgstPaise), 250f, y, pBody)
                c.drawText(MoneyFormatter.formatPaise(slab.sgstPaise), 345f, y, pBody)
                c.drawText(MoneyFormatter.formatPaise(slab.igstPaise), 440f, y, pBody)
                y += 15f
            }
            doc.finishPage(page)
            FileOutputStream(file).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        file
    }

    private fun renderA4Invoice(doc: PdfDocument, store: StoreSettings, sale: Sale) {
        val pageWidth = 595
        val pageHeight = 842
        val itemsPerFirstPage = 16
        val itemsPerNextPage = 26

        val chunks = mutableListOf<List<SaleItem>>()
        if (sale.items.isEmpty()) {
            chunks.add(emptyList())
        } else {
            chunks.add(sale.items.take(itemsPerFirstPage))
            var idx = itemsPerFirstPage
            while (idx < sale.items.size) {
                chunks.add(sale.items.subList(idx, (idx + itemsPerNextPage).coerceAtMost(sale.items.size)))
                idx += itemsPerNextPage
            }
        }

        val pStore = paint(18f, bold = true)
        val pSub = paint(9.5f, bold = false, color = Color.DKGRAY)
        val pHead = paint(10f, bold = true)
        val pBody = paint(9.5f, bold = false)
        val pSmall = paint(8.5f, bold = false, color = Color.DKGRAY)

        chunks.forEachIndexed { pageIdx, pageItems ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIdx + 1).create())
            val c = page.canvas
            var y = 40f

            if (sale.status == SaleStatus.VOIDED) {
                drawWatermark(c, "VOIDED / CANCELLED", pageWidth, pageHeight)
            }

            if (pageIdx == 0) {
                c.drawText(store.name, 40f, y, pStore); y += 16f
                if (store.address.isNotBlank()) { c.drawText(store.address, 40f, y, pSub); y += 13f }
                val contactLine = listOfNotNull(
                    store.phone.takeIf { it.isNotBlank() }?.let { "Ph: $it" },
                    store.email.takeIf { it.isNotBlank() },
                    store.gstin.takeIf { it.isNotBlank() }?.let { "GSTIN: $it" }
                ).joinToString("  |  ")
                if (contactLine.isNotBlank()) { c.drawText(contactLine, 40f, y, pSub); y += 13f }
                val stateLabel = IndianStates.findByCode(store.stateCode)?.label ?: store.stateCode
                c.drawText("State: $stateLabel", 40f, y, pSub); y += 16f

                c.drawLine(40f, y, 555f, y, linePaint()); y += 16f
                c.drawText("TAX INVOICE", 40f, y, paint(13f, bold = true))
                c.drawText("Invoice No: ${sale.invoiceNo}", 340f, y, pHead); y += 14f
                c.drawText("Bill To: ${sale.customerName}", 40f, y, pHead)
                c.drawText("Date: ${DateTimeUtils.formatDateTime(sale.createdAt, store.timezone)}", 340f, y, pBody); y += 13f
                val posState = IndianStates.findByCode(sale.placeOfSupplyStateCode)?.label ?: sale.placeOfSupplyStateCode
                c.drawText(
                    if (sale.customerGstin.isNotBlank()) "Customer GSTIN: ${sale.customerGstin}" else "Place of Supply: $posState",
                    40f, y, pSmall
                )
                c.drawText("Cashier: ${sale.cashierName}", 340f, y, pSmall); y += 18f
            } else {
                c.drawText("${store.name} - Invoice ${sale.invoiceNo} (Page ${pageIdx + 1})", 40f, y, pHead)
                y += 18f
            }

            c.drawLine(40f, y, 555f, y, linePaint()); y += 14f
            c.drawText("#", 40f, y, pHead)
            c.drawText("Item / HSN", 62f, y, pHead)
            c.drawText("Qty", 265f, y, pHead)
            c.drawText("Rate", 315f, y, pHead)
            c.drawText("Taxable", 385f, y, pHead)
            c.drawText("GST", 450f, y, pHead)
            c.drawText("Total", 505f, y, pHead)
            y += 8f
            c.drawLine(40f, y, 555f, y, linePaint()); y += 15f

            val baseIdx = if (pageIdx == 0) 0 else itemsPerFirstPage + (pageIdx - 1) * itemsPerNextPage
            pageItems.forEachIndexed { i, item ->
                c.drawText("${baseIdx + i + 1}", 40f, y, pBody)
                c.drawText(item.descriptionSnapshot.take(30), 62f, y, pBody)
                c.drawText("${QuantityScale.formatScaled(item.qtyScaled)} ${item.unitSnapshot}", 265f, y, pBody)
                c.drawText(MoneyFormatter.formatPaise(item.ratePaise, false), 315f, y, pBody)
                c.drawText(MoneyFormatter.formatPaise(item.taxablePaise, false), 385f, y, pBody)
                c.drawText("${item.gstRateBps / 100}%", 450f, y, pBody)
                c.drawText(MoneyFormatter.formatPaise(item.totalPaise, false), 505f, y, pBody)
                y += 12f
                if (item.hsnSnapshot.isNotBlank()) {
                    c.drawText("HSN: ${item.hsnSnapshot}", 62f, y, pSmall)
                    y += 12f
                } else {
                    y += 4f
                }
            }

            if (pageIdx == chunks.lastIndex) {
                y += 8f
                c.drawLine(40f, y, 555f, y, linePaint()); y += 16f
                c.drawText("Taxable Amount:", 350f, y, pBody)
                c.drawText(MoneyFormatter.formatPaise(sale.taxablePaise), 480f, y, pBody); y += 14f
                if (sale.isInterState) {
                    c.drawText("IGST Total:", 350f, y, pBody)
                    c.drawText(MoneyFormatter.formatPaise(sale.igstPaise), 480f, y, pBody); y += 14f
                } else {
                    c.drawText("CGST Total:", 350f, y, pBody)
                    c.drawText(MoneyFormatter.formatPaise(sale.cgstPaise), 480f, y, pBody); y += 14f
                    c.drawText("SGST Total:", 350f, y, pBody)
                    c.drawText(MoneyFormatter.formatPaise(sale.sgstPaise), 480f, y, pBody); y += 14f
                }
                if (sale.discountPaise > 0L) {
                    c.drawText("Discounts Applied:", 350f, y, pBody)
                    c.drawText("-${MoneyFormatter.formatPaise(sale.discountPaise)}", 480f, y, pBody); y += 14f
                }
                if (sale.roundOffPaise != 0L) {
                    c.drawText("Round Off:", 350f, y, pBody)
                    c.drawText(MoneyFormatter.formatPaise(sale.roundOffPaise), 480f, y, pBody); y += 14f
                }
                c.drawLine(350f, y, 555f, y, linePaint()); y += 16f
                c.drawText("Grand Total:", 350f, y, paint(12f, bold = true))
                c.drawText(MoneyFormatter.formatPaise(sale.totalPaise), 475f, y, paint(12f, bold = true)); y += 18f

                c.drawText("Amount in words: ${IndianRupeesInWords.convert(sale.totalPaise)}", 40f, y, pSmall); y += 16f
                val payText = sale.payments.joinToString(" | ") { "${it.method.label}: ${MoneyFormatter.formatPaise(it.amountPaise)}" }
                if (payText.isNotBlank()) {
                    c.drawText("Payments: $payText", 40f, y, pSmall); y += 14f
                }
                c.drawText("Paid: ${MoneyFormatter.formatPaise(sale.paidPaise)}   |   Balance Due: ${MoneyFormatter.formatPaise(sale.balanceDuePaise)}", 40f, y, pBody); y += 22f
                c.drawLine(40f, y, 555f, y, linePaint()); y += 16f
                c.drawText(store.invoiceFooter.take(90), 40f, y, pSmall)
            }
            doc.finishPage(page)
        }
    }

    private fun renderThermalReceipt(doc: PdfDocument, store: StoreSettings, sale: Sale, format: InvoiceFormat) {
        val width = format.widthPoints
        val is58 = format == InvoiceFormat.THERMAL_58MM
        val margin = if (is58) 8f else 12f
        val contentRight = width - margin
        val estHeight = (260 + sale.items.size * 28 + sale.payments.size * 14).coerceAtLeast(340)

        val page = doc.startPage(PdfDocument.PageInfo.Builder(width, estHeight, 1).create())
        val c = page.canvas
        val pTitle = paint(if (is58) 10f else 12f, bold = true)
        val pHead = paint(if (is58) 7.5f else 8.5f, bold = true)
        val pBody = paint(if (is58) 7f else 8f, bold = false)

        var y = 20f
        if (sale.status == SaleStatus.VOIDED) {
            c.drawText("*** VOIDED ***", margin, y, pHead); y += 12f
        }
        c.drawText(store.name.take(28), margin, y, pTitle); y += 12f
        if (store.gstin.isNotBlank()) { c.drawText("GSTIN: ${store.gstin}", margin, y, pBody); y += 10f }
        if (store.phone.isNotBlank()) { c.drawText("Ph: ${store.phone}", margin, y, pBody); y += 10f }
        c.drawLine(margin, y, contentRight, y, linePaint()); y += 11f
        c.drawText("Inv: ${sale.invoiceNo}", margin, y, pHead); y += 10f
        c.drawText(DateTimeUtils.formatDateTime(sale.createdAt, store.timezone), margin, y, pBody); y += 10f
        c.drawText("Cust: ${sale.customerName.take(24)}", margin, y, pBody); y += 10f
        c.drawLine(margin, y, contentRight, y, linePaint()); y += 11f

        for (item in sale.items) {
            c.drawText(item.descriptionSnapshot.take(if (is58) 22 else 30), margin, y, pHead); y += 10f
            val qtyStr = "${QuantityScale.formatScaled(item.qtyScaled)} x ${MoneyFormatter.formatPaise(item.ratePaise, false)}"
            c.drawText(qtyStr, margin, y, pBody)
            c.drawText(MoneyFormatter.formatPaise(item.totalPaise), contentRight - 46f, y, pBody)
            y += 12f
        }
        c.drawLine(margin, y, contentRight, y, linePaint()); y += 11f
        c.drawText("Taxable: ${MoneyFormatter.formatPaise(sale.taxablePaise)}", margin, y, pBody); y += 10f
        if (sale.isInterState) {
            c.drawText("IGST: ${MoneyFormatter.formatPaise(sale.igstPaise)}", margin, y, pBody); y += 10f
        } else {
            c.drawText("CGST+SGST: ${MoneyFormatter.formatPaise(sale.cgstPaise + sale.sgstPaise)}", margin, y, pBody); y += 10f
        }
        c.drawText("TOTAL: ${MoneyFormatter.formatPaise(sale.totalPaise)}", margin, y, pTitle); y += 13f
        sale.payments.forEach { p ->
            c.drawText("${p.method.label}: ${MoneyFormatter.formatPaise(p.amountPaise)}", margin, y, pBody); y += 10f
        }
        y += 6f
        c.drawText("Thank you!", margin, y, pBody)
        doc.finishPage(page)
    }

    fun buildShareIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun drawWatermark(c: Canvas, text: String, width: Int, height: Int) {
        c.save()
        c.rotate(-35f, width / 2f, height / 2f)
        val wp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(45, 220, 38, 38)
            textSize = 42f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        c.drawText(text, width / 2f, height / 2f, wp)
        c.restore()
    }

    private fun paint(size: Float, bold: Boolean, color: Int = Color.BLACK): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            this.color = color
        }

    private fun linePaint(): Paint = Paint().apply {
        color = Color.LTGRAY
        strokeWidth = 1f
    }
}
