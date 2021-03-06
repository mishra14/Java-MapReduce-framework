package edu.upenn.cis455.mapreduce.master;

import java.io.*;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.*;
import edu.upenn.cis455.mapreduce.worker.HttpResponse;

/**
 * This class is master servlet class that is responsible for receiving jobs
 * from the user interface and pass the jobs to the workers.
 *
 * @author cis455
 */

public class MasterServlet extends HttpServlet
{

	/** The Constant serialVersionUID. */
	static final long serialVersionUID = 455555001;

	/** The socket. */
	private Socket socket;

	/** The Constant css. */
	private static final String css = "<head>" + "<style>" + "table, th, td {"
			+ "    border: 1px solid black;" + "    border-collapse: collapse;"
			+ "}" + "th, td {" + "    padding: 5px;" + "}" + "</style>"
			+ "</head>";

	/** The workers. */
	private Map<String, WorkerStatus> workers;

	/** The job queue. */
	private Queue<Job> jobQueue;

	/* (non-Javadoc)
	 * @see javax.servlet.GenericServlet#init()
	 */
	public void init()
	{
		System.out.println("Master servlet ready");
		workers = new HashMap<String, WorkerStatus>();
		jobQueue = new Queue<Job>();
	}

	/* (non-Javadoc)
	 * @see javax.servlet.http.HttpServlet#doPost(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
	 */
	public void doPost(HttpServletRequest request, HttpServletResponse response)
			throws IOException
	{
		String pathInfo = request.getPathInfo();
		StringBuilder pageContent = new StringBuilder();
		if (pathInfo == null || pathInfo.equalsIgnoreCase("/")) // homepage
		{
			response.sendRedirect("/");
		}
		else if (pathInfo.equalsIgnoreCase("/newjob"))
		{
			// create new job
			String jobName = request.getParameter("job");
			String inputDirectory = request.getParameter("inputdir");
			String outputDirectory = request.getParameter("outputdir");
			String mapThreads = request.getParameter("mapthreads");
			String reduceThreads = request.getParameter("reducethreads");
			pageContent.append("creating new job with following params - <br>"
					+ "Job : " + request.getParameter("job") + "<br>"
					+ "Input Directory : " + request.getParameter("inputdir")
					+ "<br>" + "Output Directory : "
					+ request.getParameter("outputdir") + "<br>"
					+ "Map Threads : " + request.getParameter("mapthreads")
					+ "<br>" + "Reduce Threads : "
					+ request.getParameter("reducethreads") + "<br>");
			if (!inputDirectory.startsWith("/"))
			{
				inputDirectory = "/" + inputDirectory;
			}
			if (inputDirectory.endsWith("/"))
			{
				inputDirectory = inputDirectory.substring(0,
						inputDirectory.length() - 1);
			}
			if (!outputDirectory.startsWith("/"))
			{
				outputDirectory = "/" + outputDirectory;
			}
			if (outputDirectory.endsWith("/"))
			{
				outputDirectory = outputDirectory.substring(0,
						outputDirectory.length() - 1);
			}
			Job newJob = new Job(jobName, inputDirectory, outputDirectory,
					mapThreads, reduceThreads);
			jobQueue.enqueue(newJob);
			if (jobQueue.getSize() == 1)
			{
				// start this job as this is the only job
				assignJob();
			}
			response.sendRedirect("/master/status");

		}
		PrintWriter out = response.getWriter();
		out.print("<html>" + css + pageContent.toString() + "</html>");
		response.flushBuffer();
	}

	/* (non-Javadoc)
	 * @see javax.servlet.http.HttpServlet#doGet(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
	 */
	public void doGet(HttpServletRequest request, HttpServletResponse response)
			throws java.io.IOException
	{
		System.out.println("master : get received");
		response.setContentType("text/html");

		String pathInfo = request.getPathInfo();
		StringBuilder pageContent = new StringBuilder();
		if (pathInfo == null || pathInfo.equalsIgnoreCase("/")) // homepage
		{
			pageContent.append(getHomePage());
		}
		else if (pathInfo.equalsIgnoreCase("/status"))
		{
			pageContent.append(getStatusPage());
		}
		else if (pathInfo.equalsIgnoreCase("/workerstatus"))
		{
			String port = request.getParameter("port");
			WorkerStatus.statusType type = WorkerStatus.statusType
					.valueOf(request.getParameter("status"));
			String keysRead = request.getParameter("keysread");
			String keysWritten = request.getParameter("keyswritten");
			String job = request.getParameter("job");
			WorkerStatus workerStatus = new WorkerStatus(port, job, keysRead,
					keysWritten, type);
			workers.put(request.getRemoteAddr() + ":" + port, workerStatus);

			if (type == WorkerStatus.statusType.idle)
			{
				// check if any job is running
				Job firstJob = (jobQueue.getSize() > 0) ? jobQueue.getQueue()
						.get(0) : null;
				if (firstJob != null && firstJob.getWorkers() == null)
				{
					// if not then assign a new job
					assignJob();
				}
				else if (firstJob != null && firstJob.getWorkers() != null)
				{
					boolean stillWorking = false;
					// a job is running; check if this is done
					for (String workerName : firstJob.getWorkers())
					{
						WorkerStatus worker = workers.get(workerName);
						if (worker != null
								&& worker.getStatus() != WorkerStatus.statusType.idle)
						{
							stillWorking = true;
							break;
						}
					}
					if (!stillWorking)
					{
						// this job is done
						// remove from queue
						jobQueue.dequeue();
						assignJob();
					}
				}
				// else do nothing
			}
			else if (type == WorkerStatus.statusType.waiting)
			{
				System.out.println("master : waiting received");
				// check if all current job workers have moved to waiting

				// check if any job is running
				Job firstJob = (jobQueue.getSize() > 0) ? jobQueue.getQueue()
						.get(0) : null;
				if (firstJob != null && firstJob.getWorkers() != null
						&& job.equals(firstJob.getJobName()))
				{
					System.out.println("master : job - " + firstJob);
					boolean stillWorking = false;
					// a job is running; check if it's map phase is done
					for (String workerName : firstJob.getWorkers())
					{
						System.out.println("master : checking worker - "
								+ workerName);
						WorkerStatus worker = workers.get(workerName);
						if (worker != null
								&& worker.getStatus() != WorkerStatus.statusType.waiting)
						{
							System.out
									.println("master : worker still working - "
											+ workerName);
							stillWorking = true;
							break;
						}
					}
					if (!stillWorking)
					{
						// this job's map phase is done
						// assign reduce
						sendJob(false, firstJob);
					}
				}
				// else do nothing

			}
			// check if the current job is done
			// if done then assign the next job, if any
			// else do nothing
			System.out.println("master : " + "Updated worker - "
					+ workerStatus.toString());
			pageContent.append("Updated worker - " + workerStatus.toString());
		}
		else
		{
			pageContent.append("<head><title>Master</title></head>"
					+ "<body>Unknown Url<br><br>"
					+ "Please use <a href=\"/master/status\">status page</a> "
					+ " to give a new job and check worker status !<br>"
					+ "</body>");
		}
		PrintWriter out = response.getWriter();
		out.print("<html>" + css + pageContent.toString() + "</html>");
		response.flushBuffer();
	}

	/**
	 * Gets the home page.
	 *
	 * @return the home page
	 */
	private String getHomePage()
	{
		return "<head><title>Master</title></head>"
				+ "<body><h2>Master Servlet home page </h2><br><br>"
				+ "Please use <a href=\"/master/status\">status page</a> "
				+ " to give a new job and check worker status !<br>"
				+ "</body>";
	}

	/**
	 * Gets the status page.
	 *
	 * @return the status page
	 */
	private String getStatusPage()
	{
		StringBuilder pageContent = new StringBuilder();

		pageContent
				.append("<head><title>Master</title></head>"
						+ "<body><h2>Master Servlet status page - Ankit Mishra    mankit</h2><br>");
		pageContent.append("Submit new map reduce job - <br><br>"
				+ "<form action=\"/master/newjob\" method=\"post\">"
				+ "Job class name:<br>"
				+ "<input type=\"text\" name =\"job\"><br>"
				+ "Input directory(relative to worker storage directory):<br>"
				+ "<input type=\"text\" name =\"inputdir\"><br>"
				+ "Output directory(relative to worker storage directory):<br>"
				+ "<input type=\"text\" name =\"outputdir\"><br>"
				+ "Number of map threads:<br>"
				+ "<input type=\"text\" name =\"mapthreads\"><br>"
				+ "Number of reduce threads:<br>"
				+ "<input type=\"text\" name =\"reducethreads\"><br>"
				+ "<input type=\"submit\" value =\"Submit\">"
				+ "</form><br><br>");

		pageContent.append("Worker status - <br>" + "<table>"
				+ "<th>IP:Port</th>" + "<th>Status</th>" + "<th>Job</th>"
				+ "<th>Keys Read</th>" + "<th>Keys Written</th>");
		for (Map.Entry<String, WorkerStatus> entry : workers.entrySet())
		{
			long now = (new Date()).getTime();
			if (now - entry.getValue().getTimestamp() <= 30000)
			{
				pageContent
						.append("<tr>" + "<td>" + entry.getKey() + "</td>"
								+ "<td>" + entry.getValue().getStatus()
								+ "</td>" + "<td>" + entry.getValue().getJob()
								+ "</td>" + "<td>"
								+ entry.getValue().getKeysRead() + "</td>"
								+ "<td>" + entry.getValue().getKeysWritten()
								+ "</td>" + "</tr>");
			}

		}

		pageContent.append("</table><br><br>Job Queue - <br>" + "<table>"
				+ "<th>Job Name</th>" + "<th>Input Directory</th>"
				+ "<th>Output Directory</th>"
				+ "<th>Number of Map Threads</th>"
				+ "<th>Number of Reduce Threads</th>" + "<th>Status</th>");
		for (Job job : jobQueue.getQueue())
		{
			pageContent.append("<tr>" + "<td>" + job.getJobName() + "</td>"
					+ "<td>" + job.getInputDirectory() + "</td>" + "<td>"
					+ job.getOutputDirectory() + "</td>" + "<td>"
					+ job.getMapThreads() + "</td>" + "<td>"
					+ job.getReduceThreads() + "</td>" + "<td>"
					+ ((job.getWorkers() == null) ? "Pending" : "In Process")
					+ "</td>" + "</tr>");
		}
		pageContent.append("</table></body>");

		return pageContent.toString();
	}

	/**
	 * Assign job.
	 *
	 * @throws IOException
	 *             Signals that an I/O exception has occurred.
	 */
	private void assignJob() throws IOException
	{
		if (jobQueue.getSize() > 0)
		{
			Job newJob = jobQueue.getQueue().get(0);
			if (newJob != null)
			{
				ArrayList<String> jobWorkers = new ArrayList<String>();
				for (Map.Entry<String, WorkerStatus> entry : workers.entrySet())
				{
					long now = (new Date()).getTime();
					if (now - entry.getValue().getTimestamp() <= 30000)
					{
						// send new post /runmap request to each active worker
						jobWorkers.add(entry.getKey());
					}
				}
				if (jobWorkers.size() > 0)
				{
					// job was assigned to atleast 1 worker
					// send the job to these workers
					newJob.setWorkers(jobWorkers);
					sendJob(true, newJob);
				}
			}
		}
	}

	/**
	 * Send job.
	 *
	 * @param isMap
	 *            the is map
	 * @param job
	 *            the job
	 * @throws IOException
	 *             Signals that an I/O exception has occurred.
	 */
	public void sendJob(boolean isMap, Job job) throws IOException
	{
		if (isMap)
		{
			List<String> updatedWorkers = new ArrayList<String>();
			String jobName = job.getJobName();
			String inputDirectory = job.getInputDirectory();
			String numThreads = job.getMapThreads();
			String numWorkers = "" + job.getWorkers().size();
			StringBuilder workerString = new StringBuilder();
			for (int i = 0; i < job.getWorkers().size(); i++)
			{
				workerString.append("worker" + (i + 1) + "="
						+ job.getWorkers().get(i));
				if (i < job.getWorkers().size() - 1)
				{
					workerString.append("&");
				}
			}
			String body = "job=" + jobName + "&" + "input=" + inputDirectory
					+ "&" + "numthreads=" + numThreads + "&" + "numworkers="
					+ numWorkers + "&" + workerString.toString();
			for (String worker : job.getWorkers())
			{
				String workerUrl = "http://" + worker + "/worker/runmap";
				System.out.println("master : sending map request to - "
						+ workerUrl);
				URL url = new URL(workerUrl);
				String host = url.getHost();
				int port = url.getPort() == -1 ? url.getDefaultPort() : url
						.getPort();
				socket = new Socket(host, port);
				PrintWriter clientSocketOut = new PrintWriter(
						new OutputStreamWriter(socket.getOutputStream()));
				clientSocketOut.print("POST " + url.toString()
						+ " HTTP/1.0\r\n");
				clientSocketOut.print("Content-Length:" + body.length()
						+ "\r\n");
				clientSocketOut
						.print("Content-Type:application/x-www-form-urlencoded\r\n");
				clientSocketOut.print("\r\n");
				clientSocketOut.print(body);
				clientSocketOut.print("\r\n");
				clientSocketOut.print("\r\n");
				clientSocketOut.flush();
				HttpResponse response = parseResponse();
				if (!response.getResponseCode().equalsIgnoreCase("200"))
				{
					System.out.println("Master : worker " + worker
							+ "did not accept the map job");
				}
				else
				{
					updatedWorkers.add(worker);
				}
			}
			job.setWorkers(updatedWorkers);
		}
		else
		{
			List<String> updatedWorkers = new ArrayList<String>();
			String jobName = job.getJobName();
			String outputDirectory = job.getOutputDirectory();
			String numThreads = job.getMapThreads();

			String body = "job=" + jobName + "&" + "output=" + outputDirectory
					+ "&" + "numthreads=" + numThreads;
			for (String worker : job.getWorkers())
			{
				String workerUrl = "http://" + worker + "/worker/runreduce";
				System.out.println("master : sending reduce request to - "
						+ workerUrl);
				URL url = new URL(workerUrl);
				String host = url.getHost();
				int port = url.getPort() == -1 ? url.getDefaultPort() : url
						.getPort();
				socket = new Socket(host, port);
				PrintWriter clientSocketOut = new PrintWriter(
						new OutputStreamWriter(socket.getOutputStream()));
				clientSocketOut.print("POST " + url.toString()
						+ " HTTP/1.0\r\n");
				clientSocketOut.print("Content-Length:" + body.length()
						+ "\r\n");
				clientSocketOut
						.print("Content-Type:application/x-www-form-urlencoded\r\n");
				clientSocketOut.print("\r\n");
				clientSocketOut.print(body);
				clientSocketOut.print("\r\n");
				clientSocketOut.print("\r\n");
				clientSocketOut.flush();
				HttpResponse response = parseResponse();
				if (!response.getResponseCode().equalsIgnoreCase("200"))
				{
					System.out.println("Master : worker " + worker
							+ "did not accept the reduce job");
				}
				else
				{
					updatedWorkers.add(worker);
				}
			}
			job.setWorkers(updatedWorkers);
		}
	}

	/**
	 * Parses the response.
	 *
	 * @return the http response
	 * @throws IOException
	 *             Signals that an I/O exception has occurred.
	 */
	public HttpResponse parseResponse() throws IOException
	{
		InputStream socketInputStream = socket.getInputStream();
		InputStreamReader socketInputStreamReader = new InputStreamReader(
				socketInputStream);
		BufferedReader socketBufferedReader = new BufferedReader(
				socketInputStreamReader);
		HttpResponse response = parseResponse(socketBufferedReader);
		socketBufferedReader.close();
		socketInputStreamReader.close();
		socketInputStream.close();
		socket.close();
		return response;
	}

	/**
	 * parses the http response from the server into an HttpResponse object.
	 *
	 * @param in
	 *            the in
	 * @return the http response
	 * @throws IOException
	 *             Signals that an I/O exception has occurred.
	 */
	public HttpResponse parseResponse(BufferedReader in) throws IOException
	{
		HttpResponse response = new HttpResponse();
		String line = in.readLine();
		if (line != null)
		{
			String[] firstLineSplit = line.trim().split(" ", 3);
			if (firstLineSplit.length < 3)
			{
				return null;
			}
			if (firstLineSplit[0].trim().split("/").length < 2)
			{
				return null;
			}
			response.setProtocol((firstLineSplit[0].trim().split("/")[0]));
			response.setVersion((firstLineSplit[0].trim().split("/")[1]));
			response.setResponseCode(firstLineSplit[1].trim());
			response.setResponseCodeString(firstLineSplit[2].trim());
			Map<String, List<String>> headers = new HashMap<String, List<String>>();
			while ((line = in.readLine()) != null)
			{
				if (line.equals(""))
				{
					break;
				}
				String[] lineSplit = line.trim().split(":", 2);
				if (lineSplit.length == 2)
				{
					if (headers.containsKey(lineSplit[0].toLowerCase().trim()))
					{
						headers.get(lineSplit[0]).add(lineSplit[1].trim());
					}
					else
					{
						ArrayList<String> values = new ArrayList<String>();
						values.add(lineSplit[1].trim());
						headers.put(lineSplit[0].toLowerCase().trim(), values);
					}

				}
			}
			StringBuilder responseBody = new StringBuilder();
			while ((line = in.readLine()) != null)
			{
				responseBody.append(line + "\r\n");
			}
			response.setHeaders(headers);
			response.setData(responseBody.toString());
		}
		else
		{
			return null;
		}
		return response;
	}

}