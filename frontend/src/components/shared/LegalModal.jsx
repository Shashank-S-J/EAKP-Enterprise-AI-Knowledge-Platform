import { useEffect, useRef } from 'react';

const TERMS_CONTENT = [
	{ type: 'h2', text: 'Terms of Service' },
	{
		type: 'meta',
		text: 'Effective Date: January 1, 2025 · Last Updated: January 1, 2025',
	},

	{ type: 'h3', text: '1. Acceptance of Terms' },
	{
		type: 'p',
		text: 'By accessing or using the Enterprise AI Knowledge Platform ("EAKP", "Service"), you agree to be bound by these Terms of Service ("Terms"). If you do not agree, you may not use the Service.',
	},

	{ type: 'h3', text: '2. Description of Service' },
	{
		type: 'p',
		text: 'EAKP is an enterprise AI-powered knowledge management platform that enables organizations to upload, process, and query their internal documents using artificial intelligence. The Service includes document ingestion, AI-powered chat, and administrative tools.',
	},

	{ type: 'h3', text: '3. User Accounts' },
	{
		type: 'ul',
		items: [
			'You must provide accurate, complete registration information.',
			'You are responsible for maintaining the confidentiality of your account credentials.',
			'You must notify us immediately of any unauthorized use of your account.',
			'One person or legal entity may not maintain more than one account.',
		],
	},

	{ type: 'h3', text: '4. Acceptable Use' },
	{ type: 'p', text: 'You agree not to:' },
	{
		type: 'ul',
		items: [
			'Upload any content that is unlawful, harmful, threatening, abusive, or otherwise objectionable.',
			'Attempt to gain unauthorized access to any part of the Service.',
			'Use the Service to infringe upon the intellectual property rights of others.',
			'Reverse engineer, decompile, or disassemble any aspect of the Service.',
			'Use the Service in any way that could damage, disable, or impair the platform.',
			'Share your account credentials with unauthorized individuals.',
		],
	},

	{ type: 'h3', text: '5. Intellectual Property' },
	{
		type: 'ul',
		items: [
			{
				bold: 'Your Content:',
				text: ' You retain ownership of all documents and data you upload. By uploading content, you grant EAKP a limited license to process, store, and index your content solely for the purpose of providing the Service.',
			},
			{
				bold: 'Our Platform:',
				text: ' The Service, including its design, code, AI models, and documentation, is owned by EAKP and protected by intellectual property laws.',
			},
		],
	},

	{ type: 'h3', text: '6. AI-Generated Responses' },
	{
		type: 'ul',
		items: [
			'EAKP uses artificial intelligence to generate responses based on your uploaded documents.',
			'AI responses may contain inaccuracies. You should independently verify critical information.',
			'EAKP does not guarantee the accuracy, completeness, or reliability of AI-generated content.',
		],
	},

	{ type: 'h3', text: '7. Data Processing' },
	{
		type: 'ul',
		items: [
			'Documents are processed and stored securely within your workspace.',
			'Vector embeddings are generated for search and retrieval purposes.',
			'Your data is isolated within your workspace and not shared across workspaces.',
		],
	},

	{ type: 'h3', text: '8. Service Availability' },
	{
		type: 'ul',
		items: [
			'We strive for high availability but do not guarantee uninterrupted service.',
			'We may perform maintenance that temporarily affects availability.',
			'We reserve the right to modify or discontinue the Service with reasonable notice.',
		],
	},

	{ type: 'h3', text: '9. Limitation of Liability' },
	{
		type: 'p',
		text: 'To the maximum extent permitted by law, EAKP shall not be liable for any indirect, incidental, special, consequential, or punitive damages arising from your use of the Service.',
	},

	{ type: 'h3', text: '10. Termination' },
	{
		type: 'ul',
		items: [
			'You may terminate your account at any time.',
			'We may suspend or terminate accounts that violate these Terms.',
			'Upon termination, your data will be deleted within 30 days.',
		],
	},

	{ type: 'h3', text: '11. Changes to Terms' },
	{
		type: 'p',
		text: 'We may update these Terms from time to time. We will notify you of material changes via the Service or email. Continued use after changes constitutes acceptance.',
	},

	{ type: 'h3', text: '12. Governing Law' },
	{
		type: 'p',
		text: 'These Terms are governed by and construed in accordance with applicable laws. Any disputes shall be resolved through binding arbitration.',
	},

	{ type: 'h3', text: '13. Contact' },
	{
		type: 'p',
		text: 'For questions about these Terms, contact your organization\'s EAKP administrator or reach out to our support team.',
	},
];

const PRIVACY_CONTENT = [
	{ type: 'h2', text: 'Privacy Policy' },
	{
		type: 'meta',
		text: 'Effective Date: January 1, 2025 · Last Updated: January 1, 2025',
	},

	{ type: 'h3', text: '1. Introduction' },
	{
		type: 'p',
		text: 'This Privacy Policy explains how the Enterprise AI Knowledge Platform ("EAKP", "we", "us") collects, uses, and protects your personal information when you use our Service.',
	},

	{ type: 'h3', text: '2. Information We Collect' },

	{ type: 'h4', text: '2.1 Account Information' },
	{
		type: 'ul',
		items: [
			{ bold: 'Registration data:', text: ' Full name, email address, workspace name.' },
			{ bold: 'Authentication data:', text: ' Encrypted password hash, OAuth provider identifiers (Google, GitHub).' },
		],
	},

	{ type: 'h4', text: '2.2 Uploaded Content' },
	{
		type: 'ul',
		items: [
			'Documents you upload (PDF, DOCX, TXT, PPTX, XLSX, CSV, MD, HTML).',
			'Document metadata (filename, file size, upload date, processing status).',
		],
	},

	{ type: 'h4', text: '2.3 Usage Data' },
	{
		type: 'ul',
		items: [
			'Chat conversations and queries.',
			'AI-generated responses and source citations.',
			'Feature usage patterns (e.g., document uploads, chat sessions).',
		],
	},

	{ type: 'h4', text: '2.4 Technical Data' },
	{
		type: 'ul',
		items: [
			'Browser type and version.',
			'IP address (for security and abuse prevention).',
			'Device information.',
		],
	},

	{ type: 'h4', text: '2.5 Cookies' },
	{
		type: 'ul',
		items: [
			{ bold: 'Strictly Necessary Cookies:', text: ' Authentication tokens required for the Service to function. These cannot be disabled.' },
			{ bold: 'Functional Cookies:', text: ' Preferences such as sidebar state, theme settings. These require your consent and can be managed via the cookie consent banner.' },
		],
	},
	{ type: 'p', text: 'For detailed cookie information, see Section 7 below.' },

	{ type: 'h3', text: '3. How We Use Your Information' },
	{ type: 'p', text: 'We use your information to:' },
	{
		type: 'ul',
		items: [
			'Provide, maintain, and improve the Service.',
			'Authenticate your identity and manage your account.',
			'Process and index your documents for AI-powered retrieval.',
			'Generate AI responses to your queries.',
			'Ensure security and prevent unauthorized access.',
			'Comply with legal obligations.',
		],
	},

	{ type: 'h3', text: '4. Data Storage and Security' },
	{
		type: 'ul',
		items: [
			'All data is encrypted in transit (TLS) and at rest.',
			'Documents are stored in secure cloud storage with access controls.',
			'Vector embeddings are stored in isolated database collections per workspace.',
			'We implement industry-standard security measures including authentication, authorization, and audit logging.',
		],
	},

	{ type: 'h3', text: '5. Data Sharing' },
	{ type: 'p', text: 'We do not:' },
	{
		type: 'ul',
		items: [
			'Sell your personal data to third parties.',
			'Share your documents or conversations across workspaces.',
			'Use your content to train AI models beyond your workspace scope.',
		],
	},
	{ type: 'p', text: 'We may share data:' },
	{
		type: 'ul',
		items: [
			'With service providers who assist in operating the platform (cloud hosting, database services).',
			'When required by law or to protect our legal rights.',
			'In connection with a merger, acquisition, or sale of assets (with notice).',
		],
	},

	{ type: 'h3', text: '6. Data Retention' },
	{
		type: 'ul',
		items: [
			{ bold: 'Account data:', text: ' Retained while your account is active. Deleted within 30 days of account termination.' },
			{ bold: 'Uploaded documents:', text: ' Retained until you delete them or your account is terminated.' },
			{ bold: 'Chat history:', text: ' Retained until you delete conversations or your account is terminated.' },
			{ bold: 'Logs and analytics:', text: ' Retained for up to 90 days for security and debugging purposes.' },
		],
	},

	{ type: 'h3', text: '7. Cookies in Detail' },
	{
		type: 'table',
		headers: ['Cookie', 'Type', 'Purpose', 'Duration'],
		rows: [
			['eakp_at', 'Strictly Necessary', 'Access token for authentication', 'Session / Token expiry'],
			['eakp_rt', 'Strictly Necessary', 'Refresh token for session renewal', '7 days'],
			['eakp_cookie_consent', 'Strictly Necessary', 'Remembers your consent choice', '365 days'],
			['eakp_fn_*', 'Functional', 'User preferences (theme, sidebar)', '30 days'],
		],
	},
	{
		type: 'ul',
		items: [
			'Strictly necessary cookies are set without consent as required for the Service to function.',
			'Functional cookies are only set after you accept them via the consent banner.',
			'If you reject functional cookies, they are cleared and not set again during your session.',
		],
	},

	{ type: 'h3', text: '8. Your Rights' },
	{ type: 'p', text: 'Depending on your jurisdiction, you may have the right to:' },
	{
		type: 'ul',
		items: [
			{ bold: 'Access', text: ' your personal data.' },
			{ bold: 'Rectify', text: ' inaccurate personal data.' },
			{ bold: 'Delete', text: ' your personal data ("right to be forgotten").' },
			{ bold: 'Export', text: ' your data in a portable format.' },
			{ bold: 'Object', text: ' to or restrict certain processing.' },
			{ bold: 'Withdraw consent', text: ' for functional cookies at any time.' },
		],
	},
	{ type: 'p', text: 'To exercise these rights, contact your organization\'s EAKP administrator.' },

	{ type: 'h3', text: '9. Children\'s Privacy' },
	{
		type: 'p',
		text: 'EAKP is designed for enterprise use and is not intended for children under 16. We do not knowingly collect personal data from children.',
	},

	{ type: 'h3', text: '10. International Data Transfers' },
	{
		type: 'p',
		text: 'Your data may be transferred to and processed in countries other than your own. We ensure appropriate safeguards are in place for such transfers.',
	},

	{ type: 'h3', text: '11. Changes to This Policy' },
	{
		type: 'p',
		text: 'We may update this Privacy Policy periodically. We will notify you of material changes via the Service or email. The "Last Updated" date at the top reflects the most recent revision.',
	},

	{ type: 'h3', text: '12. Contact' },
	{
		type: 'p',
		text: 'For privacy-related inquiries, contact your organization\'s EAKP administrator or our data protection team.',
	},
];

function renderBlock(block, idx) {
	switch (block.type) {
		case 'h2':
			return <h2 key={idx}>{block.text}</h2>;
		case 'h3':
			return <h3 key={idx}>{block.text}</h3>;
		case 'h4':
			return <h4 key={idx}>{block.text}</h4>;
		case 'meta':
			return <p key={idx} className="legal-meta">{block.text}</p>;
		case 'p':
			return <p key={idx}>{block.text}</p>;
		case 'ul':
			return (
				<ul key={idx}>
					{block.items.map((item, j) =>
						typeof item === 'string'
							? <li key={j}>{item}</li>
							: <li key={j}><strong>{item.bold}</strong>{item.text}</li>
					)}
				</ul>
			);
		case 'table':
			return (
				<div key={idx} className="legal-table-wrap">
					<table className="legal-table">
						<thead>
							<tr>{block.headers.map((h, j) => <th key={j}>{h}</th>)}</tr>
						</thead>
						<tbody>
							{block.rows.map((row, j) => (
								<tr key={j}>{row.map((cell, k) => <td key={k}>{cell}</td>)}</tr>
							))}
						</tbody>
					</table>
				</div>
			);
		default:
			return null;
	}
}

export default function LegalModal({ type, onClose }) {
	const overlayRef = useRef(null);

	useEffect(() => {
		const handleEsc = (e) => { if (e.key === 'Escape') onClose(); };
		document.addEventListener('keydown', handleEsc);
		document.body.style.overflow = 'hidden';
		return () => {
			document.removeEventListener('keydown', handleEsc);
			document.body.style.overflow = '';
		};
	}, [onClose]);

	const handleOverlayClick = (e) => {
		if (e.target === overlayRef.current) onClose();
	};

	const blocks = type === 'terms' ? TERMS_CONTENT : PRIVACY_CONTENT;
	const title = type === 'terms' ? 'Terms of Service' : 'Privacy Policy';

	return (
		<div className="legal-modal-overlay" ref={overlayRef} onClick={handleOverlayClick}>
			<div className="legal-modal">
				<div className="legal-modal-header">
					<h2>
						<span className="material-symbols-outlined">
							{type === 'terms' ? 'gavel' : 'shield'}
						</span>
						{title}
					</h2>
					<button className="legal-modal-close" onClick={onClose} aria-label="Close">
						<span className="material-symbols-outlined">close</span>
					</button>
				</div>
				<div className="legal-modal-body">
					{blocks.map((block, i) => renderBlock(block, i))}
				</div>
				<div className="legal-modal-footer">
					<button
						className="btn-primary"
						onClick={onClose}
						style={{ width: 'auto', padding: '10px 28px' }}
					>
						I Understand
					</button>
				</div>
			</div>
		</div>
	);
}
